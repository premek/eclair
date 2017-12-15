package fr.acinq.eclair.router

import akka.actor.{ActorRef, FSM, Props}
import akka.pattern.pipe
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.Hop
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}

import scala.collection.JavaConversions._
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Success, Try}

// @formatter:off

case class ChannelDesc(id: Long, a: PublicKey, b: PublicKey)
case class RouteRequest(source: PublicKey, target: PublicKey, ignoreNodes: Set[PublicKey] = Set.empty, ignoreChannels: Set[Long] = Set.empty)
case class RouteResponse(hops: Seq[Hop], ignoreNodes: Set[PublicKey], ignoreChannels: Set[Long]) { require(hops.size > 0, "route cannot be empty") }
case class ExcludeChannel(desc: ChannelDesc) // this is used when we get a TemporaryChannelFailure, to give time for the channel to recover (note that exclusions are directed)
case class LiftChannelExclusion(desc: ChannelDesc)
case class SendRoutingState(to: ActorRef)
case class Rebroadcast(ann: Seq[RoutingMessage], origins: Map[RoutingMessage, ActorRef])

case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                  channels: Map[Long, ChannelAnnouncement],
                  updates: Map[ChannelDesc, ChannelUpdate],
                  rebroadcast: Seq[RoutingMessage],
                  stash: Seq[RoutingMessage],
                  awaiting: Seq[ChannelAnnouncement],
                  origins: Map[RoutingMessage, ActorRef],
                  localUpdates: Map[BinaryData, (ChannelDesc, ChannelUpdate)],
                  excludedChannels: Set[ChannelDesc]) // those channels are temporarily excluded from route calculation, because their node returned a TemporaryChannelFailure

sealed trait State
case object NORMAL extends State
case object WAITING_FOR_VALIDATION extends State

case object TickBroadcast
case object TickValidate
case object TickPruneStaleChannels

// @formatter:on

/**
  * Created by PM on 24/05/2016.
  */

class Router(nodeParams: NodeParams, watcher: ActorRef) extends FSM[State, Data] {

  import Router._

  import ExecutionContext.Implicits.global

  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])

  setTimer(TickBroadcast.toString, TickBroadcast, nodeParams.routerBroadcastInterval, repeat = true)
  setTimer(TickValidate.toString, TickValidate, nodeParams.routerValidateInterval, repeat = true)
  setTimer(TickPruneStaleChannels.toString, TickPruneStaleChannels, 1 day, repeat = true)

  val db = nodeParams.networkDb

  {
    log.info(s"loading network announcements from db...")
    val initChannels = db.listChannels().map(c => (c.shortChannelId -> c)).toMap
    val initNodes = (db.listNodes() match {
      case Nil => Nil
      case l => l :+ Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses, Platform.currentTime / 1000)
    }).map(n => (n.nodeId -> n)).toMap
    val initChannelUpdates = db.listChannelUpdates().map(u => (getDesc(u, initChannels(u.shortChannelId)) -> u)).toMap
    log.info(s"starting state machine")
    startWith(NORMAL, Data(initNodes, initChannels, initChannelUpdates, Nil, Nil, Nil, Map.empty, Map.empty, Set.empty))
  }

  when(NORMAL) {
    case Event(TickValidate, d) =>
      require(d.awaiting.size == 0)
      var i = 0
      // we extract a batch of channel announcements from the stash
      val (channelAnns: Seq[ChannelAnnouncement]@unchecked, otherAnns) = d.stash.partition {
        case _: ChannelAnnouncement =>
          i = i + 1
          i <= MAX_PARALLEL_JSONRPC_REQUESTS
        case _ => false
      }
      if (channelAnns.size > 0) {
        log.info(s"validating a batch of ${channelAnns.size} channels")
        watcher ! ParallelGetRequest(channelAnns)
        goto(WAITING_FOR_VALIDATION) using d.copy(stash = otherAnns, awaiting = channelAnns)
      } else stay
  }

  when(WAITING_FOR_VALIDATION) {
    case Event(ParallelGetResponse(results), d) =>
      val validated = results.flatMap {
        case IndividualResult(c, Some(tx), true) =>
          // TODO: blacklisting
          val (_, _, outputIndex) = fromShortId(c.shortChannelId)
          // let's check that the output is indeed a P2WSH multisig 2-of-2 of nodeid1 and nodeid2)
          val fundingOutputScript = write(pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
          if (tx.txOut.size < outputIndex + 1) {
            log.error(s"invalid script for shortChannelId=${c.shortChannelId}: txid=${tx.txid} does not have outputIndex=$outputIndex ann=$c")
            None
          } else if (fundingOutputScript != tx.txOut(outputIndex).publicKeyScript) {
            log.error(s"invalid script for shortChannelId=${c.shortChannelId} txid=${tx.txid} ann=$c")
            None
          } else {
            // On Android we disable the ability to detect when external channels die. If we try to use them during a
            // payment, we simply will get an error from the node that is just before the missing channel.
            //watcher ! WatchSpentBasic(self, tx, outputIndex, BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(c.shortChannelId))
            // TODO: check feature bit set
            log.debug(s"added channel channelId=${c.shortChannelId}")
            context.system.eventStream.publish(ChannelDiscovered(c, tx.txOut(outputIndex).amount))
            db.addChannel(c)
            Some(c)
          }
        case IndividualResult(c, Some(tx), false) =>
          // TODO: vulnerability if they flood us with spent funding tx?
          log.warning(s"ignoring shortChannelId=${c.shortChannelId} tx=${tx.txid} (funding tx not found in utxo)")
          // there may be a record if we have just restarted
          db.removeChannel(c.shortChannelId)
          None
        case IndividualResult(c, None, _) =>
          // TODO: blacklist?
          log.warning(s"could not retrieve tx for shortChannelId=${c.shortChannelId}")
          None
      }

      // in case we just validated our first local channel, we announce the local node
      // note that this will also make sure we always update our node announcement on restart (eg: alias, color), because
      // even if we had stored a previous announcement, it would be overriden by this more recent one
      if (!d.nodes.contains(nodeParams.nodeId) && validated.exists(isRelatedTo(_, nodeParams.nodeId))) {
        log.info(s"first local channel validated, announcing local node")
        val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses)
        self ! nodeAnn
      }

      // we also reprocess node and channel_update announcements related to channels that were processed
      val (resend, stash1) = d.stash.partition {
        case n: NodeAnnouncement => results.exists(r => isRelatedTo(r.c, n.nodeId))
        case u: ChannelUpdate => results.exists(r => r.c.shortChannelId == u.shortChannelId)
        case _ => false
      }
      resend.foreach(self ! _)
      goto(NORMAL) using d.copy(channels = d.channels ++ validated.map(c => (c.shortChannelId -> c)), rebroadcast = d.rebroadcast ++ validated, stash = stash1, awaiting = Nil)
  }

  whenUnhandled {
    case Event(ChannelStateChanged(_, _, _, _, channel.NORMAL, d: DATA_NORMAL), d1) =>
      val channelDesc = ChannelDesc(d.channelUpdate.shortChannelId, d.commitments.localParams.nodeId, d.commitments.remoteParams.nodeId)
      log.debug(s"added local channel_update for channelId=${d.channelId} update=${d.channelUpdate}")
      stay using d1.copy(localUpdates = d1.localUpdates + (d.channelId -> (channelDesc, d.channelUpdate)))

    case Event(ChannelStateChanged(_, _, _, channel.NORMAL, _, d: HasCommitments), d1) =>
      log.debug(s"removed local channel_update for channelId=${d.channelId}")
      stay using d1.copy(localUpdates = d1.localUpdates - d.channelId)

    case Event(_: ChannelStateChanged, _) => stay

    case Event(SendRoutingState(remote), Data(nodes, channels, updates, _, _, _, _, _, _)) =>
      // disabled on Android for performance reasons
      //log.debug(s"info sending all announcements to $remote: channels=${channels.size} nodes=${nodes.size} updates=${updates.size}")
      // we group and add delays to leave room for channel messages
      //context.actorOf(ThrottleForwarder.props(remote, channels.values ++ nodes.values ++ updates.values, 100, 100 millis))
      stay

    case Event(c: ChannelAnnouncement, d) =>
      log.debug(s"received channel announcement for shortChannelId=${c.shortChannelId} nodeId1=${c.nodeId1} nodeId2=${c.nodeId2}")
      if (d.channels.containsKey(c.shortChannelId) || d.awaiting.exists(_.shortChannelId == c.shortChannelId) || d.stash.contains(c)) {
        log.debug(s"ignoring $c (duplicate)")
        stay
      } else if (!Announcements.checkSigs(c)) {
        log.error(s"bad signature for announcement $c")
        sender ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
        stay
      } else {
        log.debug(s"stashing $c")
        stay using d.copy(stash = d.stash :+ c, origins = d.origins + (c -> sender))
      }

    case Event(n: NodeAnnouncement, d: Data) =>
      if (d.nodes.containsKey(n.nodeId) && d.nodes(n.nodeId).timestamp >= n.timestamp) {
        log.debug(s"ignoring announcement $n (old timestamp or duplicate)")
        stay
      } else if (!Announcements.checkSig(n)) {
        log.error(s"bad signature for announcement $n")
        sender ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
        stay
      } else if (d.nodes.containsKey(n.nodeId)) {
        log.debug(s"updated node nodeId=${n.nodeId}")
        context.system.eventStream.publish(NodeUpdated(n))
        db.updateNode(n)
        stay using d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast :+ n, origins = d.origins + (n -> sender))
      } else if (d.channels.values.exists(c => isRelatedTo(c, n.nodeId))) {
        log.debug(s"added node nodeId=${n.nodeId}")
        context.system.eventStream.publish(NodeDiscovered(n))
        db.addNode(n)
        stay using d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast :+ n, origins = d.origins + (n -> sender))
      } else if (d.awaiting.exists(c => isRelatedTo(c, n.nodeId)) || d.stash.collectFirst { case c: ChannelAnnouncement if isRelatedTo(c, n.nodeId) => c }.isDefined) {
        log.debug(s"stashing $n")
        stay using d.copy(stash = d.stash :+ n, origins = d.origins + (n -> sender))
      } else {
        log.warning(s"ignoring $n (no related channel found)")
        // there may be a record if we have just restarted
        db.removeNode(n.nodeId)
        stay
      }

    case Event(u: ChannelUpdate, d: Data) =>
      if (d.channels.contains(u.shortChannelId)) {
        val c = d.channels(u.shortChannelId)
        val desc = getDesc(u, c)
        if (d.updates.contains(desc) && d.updates(desc).timestamp >= u.timestamp) {
          log.debug(s"ignoring $u (old timestamp or duplicate)")
          stay
        } else if (!Announcements.checkSig(u, getDesc(u, d.channels(u.shortChannelId)).a)) {
          // TODO: (dirty) this will make the origin channel close the connection
          log.error(s"bad signature for announcement $u")
          sender ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
          stay
        } else if (d.updates.contains(desc)) {
          log.debug(s"updated $u")
          context.system.eventStream.publish(ChannelUpdateReceived(u))
          db.updateChannelUpdate(u)
          stay using d.copy(updates = d.updates + (desc -> u), rebroadcast = d.rebroadcast :+ u, origins = d.origins + (u -> sender))
        } else {
          log.debug(s"added $u")
          context.system.eventStream.publish(ChannelUpdateReceived(u))
          db.addChannelUpdate(u)
          stay using d.copy(updates = d.updates + (desc -> u), rebroadcast = d.rebroadcast :+ u, origins = d.origins + (u -> sender))
        }
      } else if (d.awaiting.exists(c => c.shortChannelId == u.shortChannelId) || d.stash.collectFirst { case c: ChannelAnnouncement if c.shortChannelId == u.shortChannelId => c }.isDefined) {
        log.debug(s"stashing $u")
        stay using d.copy(stash = d.stash :+ u, origins = d.origins + (u -> sender))
      } else {
        log.warning(s"ignoring announcement $u (unknown channel)")
        stay
      }

    case Event(WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId)), d)
      if d.channels.containsKey(shortChannelId) =>
      val lostChannel = d.channels(shortChannelId)
      log.info(s"funding tx of channelId=${shortChannelId.toHexString} has been spent")
      // we need to remove nodes that aren't tied to any channels anymore
      val channels1 = d.channels - lostChannel.shortChannelId
      val lostNodes = Seq(lostChannel.nodeId1, lostChannel.nodeId2).filterNot(nodeId => hasChannels(nodeId, channels1.values))
      // let's clean the db and send the events
      log.info(s"pruning shortChannelId=${shortChannelId.toHexString} (spent)")
      db.removeChannel(shortChannelId) // NB: this also removes channel updates
      context.system.eventStream.publish(ChannelLost(shortChannelId))
      lostNodes.foreach {
        case nodeId =>
          log.info(s"pruning nodeId=$nodeId (spent)")
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- lostNodes, channels = d.channels - shortChannelId, updates = d.updates.filterKeys(_.id != shortChannelId))

    case Event(TickValidate, d) => stay // ignored

    case Event(TickBroadcast, d) =>
      d.rebroadcast match {
        case Nil => stay using d.copy(origins = Map.empty)
        case _ =>
          //log.info(s"broadcasting ${d.rebroadcast.size} routing messages")
          //context.actorSelection(context.system / "*" / "switchboard") ! Rebroadcast(d.rebroadcast, d.origins)
          stay using d.copy(rebroadcast = Nil, origins = Map.empty)
      }

    case Event(TickPruneStaleChannels, d) =>
      // first we select channels that we will prune
      val staleChannels = getStaleChannels(d.channels, d.updates)
      // then we clean up the related channel updates
      val staleUpdates = d.updates.keys.filter(desc => staleChannels.contains(desc.id))
      // finally we remove nodes that aren't tied to any channels anymore
      val channels1 = d.channels -- staleChannels
      val staleNodes = d.nodes.keys.filterNot(nodeId => hasChannels(nodeId, channels1.values))
      // let's clean the db and send the events
      staleChannels.foreach {
        case shortChannelId =>
          log.info(s"pruning shortChannelId=${shortChannelId.toHexString} (stale)")
          db.removeChannel(shortChannelId) // NB: this also removes channel updates
          context.system.eventStream.publish(ChannelLost(shortChannelId))
      }
      staleNodes.foreach {
        case nodeId =>
          log.info(s"pruning nodeId=$nodeId (stale)")
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- staleNodes, channels = channels1, updates = d.updates -- staleUpdates)

    case Event(ExcludeChannel(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      val banDuration = nodeParams.channelExcludeDuration
      log.info(s"excluding shortChannelId=${shortChannelId.toHexString} from nodeId=$nodeId for duration=$banDuration")
      context.system.scheduler.scheduleOnce(banDuration, self, LiftChannelExclusion(desc))
      stay using d.copy(excludedChannels = d.excludedChannels + desc)

    case Event(LiftChannelExclusion(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      log.info(s"reinstating shortChannelId=${shortChannelId.toHexString} from nodeId=$nodeId")
      stay using d.copy(excludedChannels = d.excludedChannels - desc)

    case Event('nodes, d) =>
      sender ! d.nodes.values
      stay

    case Event('channels, d) =>
      sender ! d.channels.values
      stay

    case Event('updates, d) =>
      sender ! d.updates.values
      stay

    case Event('dot, d) =>
      graph2dot(d.nodes, d.channels) pipeTo sender
      stay

    case Event(RouteRequest(start, end, ignoreNodes, ignoreChannels), d) =>
      // we start with channel_updates of local channels
      val updates0 = d.localUpdates.values.toMap
      // we add them to the publicly-announced updates (channel_updates for announced channels will be deduped)
      val updates1 = d.updates ++ updates0
      // we then filter out the currently excluded channels
      val updates2 = updates1.filterKeys(!d.excludedChannels.contains(_))
      // we also filter out disabled channels, and channels/nodes that are blacklisted for this particular request
      val updates3 = filterUpdates(updates2, ignoreNodes, ignoreChannels)
      log.info(s"finding a route $start->$end with ignoreNodes=${ignoreNodes.map(_.toBin).mkString(",")} ignoreChannels=${ignoreChannels.map(_.toHexString).mkString(",")}")
      findRoute(start, end, updates3).map(r => RouteResponse(r, ignoreNodes, ignoreChannels)) pipeTo sender
      stay
  }

  onTransition {
    case _ -> NORMAL => log.info(s"current status channels=${nextStateData.channels.size} nodes=${nextStateData.nodes.size} updates=${nextStateData.updates.size}")
  }

  initialize()

}

object Router {

  val MAX_PARALLEL_JSONRPC_REQUESTS = 50

  def props(nodeParams: NodeParams, watcher: ActorRef) = Props(new Router(nodeParams, watcher))

  def getDesc(u: ChannelUpdate, channel: ChannelAnnouncement): ChannelDesc = {
    require(u.flags.data.size == 2, s"invalid flags length ${u.flags.data.size} != 2")
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.flags)) ChannelDesc(u.shortChannelId, channel.nodeId1, channel.nodeId2) else ChannelDesc(u.shortChannelId, channel.nodeId2, channel.nodeId1)
  }

  def isRelatedTo(c: ChannelAnnouncement, nodeId: PublicKey) = nodeId == c.nodeId1 || nodeId == c.nodeId2

  def hasChannels(nodeId: PublicKey, channels: Iterable[ChannelAnnouncement]): Boolean = channels.exists(c => isRelatedTo(c, nodeId))

  def getStaleChannels(channels: Map[Long, ChannelAnnouncement], updates: Map[ChannelDesc, ChannelUpdate]): Iterable[Long] = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update
    // so we consider stale a channel that:
    // (1) is older than 2 weeks (2*7*144 = 2016 blocks)
    //  AND
    // (2) didn't have an update during the last 2 weeks
    val staleThresholdSeconds = Platform.currentTime / 1000 - 1209600
    val staleThresholdBlocks = Globals.blockCount.get() - 2016
    val staleChannels = channels
      .filterKeys(shortChannelId => fromShortId(shortChannelId)._1 < staleThresholdBlocks) // consider only channels older than 2 weeks
      .filterKeys(shortChannelId => !updates.values.exists(u => u.shortChannelId == shortChannelId && u.timestamp >= staleThresholdSeconds)) // no update in the past 2 weeks
    staleChannels.keys
  }

  /**
    * This method is used after a payment failed, and we want to exclude some nodes/channels that we know are failing
    */
  def filterUpdates(updates: Map[ChannelDesc, ChannelUpdate], ignoreNodes: Set[PublicKey], ignoreChannels: Set[Long]) =
    updates
      .filterNot(u => ignoreNodes.map(_.toBin).contains(u._1.a) || ignoreNodes.map(_.toBin).contains(u._1.b))
      .filterNot(u => ignoreChannels.contains(u._1.id))
      .filter(u => Announcements.isEnabled(u._2.flags))

  def findRouteDijkstra(localNodeId: PublicKey, targetNodeId: PublicKey, channels: Iterable[ChannelDesc]): Seq[ChannelDesc] = {
    if (localNodeId == targetNodeId) throw CannotRouteToSelf
    case class DescEdge(desc: ChannelDesc) extends DefaultEdge
    val g = new DefaultDirectedGraph[PublicKey, DescEdge](classOf[DescEdge])
    Random.shuffle(channels).foreach(d => {
      g.addVertex(d.a)
      g.addVertex(d.b)
      g.addEdge(d.a, d.b, new DescEdge(d))
    })
    Try(Option(DijkstraShortestPath.findPathBetween(g, localNodeId, targetNodeId))) match {
      case Success(Some(path)) => path.map(_.desc)
      case _ => throw RouteNotFound
    }
  }

  def findRoute(localNodeId: PublicKey, targetNodeId: PublicKey, updates: Map[ChannelDesc, ChannelUpdate])(implicit ec: ExecutionContext): Future[Seq[Hop]] = Future {
    findRouteDijkstra(localNodeId, targetNodeId, updates.keys)
      .map(desc => Hop(desc.a, desc.b, updates(desc)))
  }

  def graph2dot(nodes: Map[PublicKey, NodeAnnouncement], channels: Map[Long, ChannelAnnouncement])(implicit ec: ExecutionContext): Future[String] = ???


}
