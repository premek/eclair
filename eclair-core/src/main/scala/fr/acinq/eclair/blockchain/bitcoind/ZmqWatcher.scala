/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.bitcoind

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorContextOps
import akka.actor.{Actor, ActorLogging, Cancellable, Props, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.KamonExt
import fr.acinq.eclair.blockchain.Monitoring.Metrics
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog
import fr.acinq.eclair.channel.BITCOIN_PARENT_TX_CONFIRMED
import fr.acinq.eclair.transactions.Scripts
import org.json4s.JsonAST.{JArray, JBool, JDecimal, JInt, JString}
import scodec.bits.ByteVector
import fr.acinq.eclair.KotlinUtils._

import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`
import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Created by PM on 21/02/2016.
 */

/**
 * A blockchain watcher that:
 *  - receives bitcoin events (new blocks and new txs) directly from the bitcoin network
 *  - also uses bitcoin-core rpc api, most notably for tx confirmation count and block count (because reorgs)
 */
class ZmqWatcher(chainHash: ByteVector32, blockCount: AtomicLong, client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  import ZmqWatcher._

  context.system.eventStream.subscribe(self, classOf[NewBlock])
  context.system.eventStream.subscribe(self, classOf[NewTransaction])
  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])

  private val watchdog = context.spawn(Behaviors.supervise(BlockchainWatchdog(chainHash, 150 seconds)).onFailure(SupervisorStrategy.resume), "blockchain-watchdog")

  // this is to initialize block count
  self ! TickNewBlock

  // @formatter:off
  private case class TriggerEvent(w: Watch, e: WatchEvent)

  private sealed trait AddWatchResult
  private case object Keep extends AddWatchResult
  private case object Ignore extends AddWatchResult
  // @formatter:on

  def receive: Receive = watching(Set(), Map(), SortedMap(), None)

  def watching(watches: Set[Watch], watchedUtxos: Map[OutPoint, Set[Watch]], block2tx: SortedMap[Long, Seq[Transaction]], nextTick: Option[Cancellable]): Receive = {

    case NewTransaction(tx) =>
      log.debug("analyzing txid={} tx={}", tx.txid, tx)
      tx.txIn
        .map(_.outPoint)
        .flatMap(watchedUtxos.get)
        .flatten // List[Watch] -> Watch
        .collect {
          case w: WatchSpentBasic =>
            self ! TriggerEvent(w, WatchEventSpentBasic(w.event))
          case w: WatchSpent =>
            self ! TriggerEvent(w, WatchEventSpent(w.event, tx))
        }

    case NewBlock(block) =>
      // using a Try because in tests we generate fake blocks
      log.debug("received blockid={}", Try(block.blockId).getOrElse(ByteVector32.Zeroes))
      nextTick.map(_.cancel()) // this may fail or succeed, worse case scenario we will have two ticks in a row (no big deal)
      log.debug("scheduling a new task to check on tx confirmations")
      // we do this to avoid herd effects in testing when generating a lots of blocks in a row
      val task = context.system.scheduler.scheduleOnce(2 seconds, self, TickNewBlock)
      context become watching(watches, watchedUtxos, block2tx, Some(task))

    case TickNewBlock =>
      client.getBlockCount.map {
        count =>
          log.debug("setting blockCount={}", count)
          blockCount.set(count)
          context.system.eventStream.publish(CurrentBlockCount(count))
      }
      checkUtxos()
      // TODO: beware of the herd effect
      KamonExt.timeFuture(Metrics.NewBlockCheckConfirmedDuration.withoutTags()) {
        Future.sequence(watches.collect { case w: WatchConfirmed => checkConfirmed(w) })
      }
      context become watching(watches, watchedUtxos, block2tx, None)

    case TriggerEvent(w, e) if watches.contains(w) =>
      log.info("triggering {}", w)
      w.replyTo ! e
      w match {
        case _: WatchSpent =>
          // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx or the commit tx
          // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
          ()
        case _ =>
          context become watching(watches - w, removeWatchedUtxos(watchedUtxos, w), block2tx, nextTick)
      }

    case CurrentBlockCount(count) =>
      val toPublish = block2tx.filterKeys(_ <= count)
      toPublish.values.flatten.foreach(tx => publish(tx))
      context become watching(watches, watchedUtxos, block2tx -- toPublish.keys, nextTick)

    case w: Watch =>

      val result = w match {
        case _ if watches.contains(w) => Ignore // we ignore duplicates

        case WatchSpentBasic(_, txid, outputIndex, _, _) =>
          // NB: we assume parent tx was published, we just need to make sure this particular output has not been spent
          client.isTransactionOutputSpendable(txid, outputIndex, includeMempool = true).collect {
            case false =>
              log.info(s"output=$outputIndex of txid=$txid has already been spent")
              self ! TriggerEvent(w, WatchEventSpentBasic(w.event))
          }
          Keep

        case WatchSpent(_, txid, outputIndex, _, _) =>
          // first let's see if the parent tx was published or not
          client.getTxConfirmations(txid).collect {
            case Some(_) =>
              // parent tx was published, we need to make sure this particular output has not been spent
              client.isTransactionOutputSpendable(txid, outputIndex, includeMempool = true).collect {
                case false =>
                  log.info(s"$txid:$outputIndex has already been spent, looking for the spending tx in the mempool")
                  client.getMempool().map { mempoolTxs =>
                    mempoolTxs.filter(tx => tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex)) match {
                      case Nil =>
                        log.warning(s"$txid:$outputIndex has already been spent, spending tx not in the mempool, looking in the blockchain...")
                        client.lookForSpendingTx(None, txid, outputIndex).map { tx =>
                          log.warning(s"found the spending tx of $txid:$outputIndex in the blockchain: txid=${tx.txid}")
                          self ! NewTransaction(tx)
                        }
                      case txs =>
                        log.info(s"found ${txs.size} txs spending $txid:$outputIndex in the mempool: txids=${txs.map(_.txid).mkString(",")}")
                        txs.foreach(tx => self ! NewTransaction(tx))
                    }
                  }
              }
          }
          Keep

        case w: WatchConfirmed =>
          checkConfirmed(w) // maybe the tx is already confirmed, in that case the watch will be triggered and removed immediately
          Keep

        case _: WatchLost => Ignore // TODO: not implemented, we ignore it silently
      }

      result match {
        case Keep =>
          log.debug("adding watch {} for {}", w, sender)
          context.watch(w.replyTo)
          context become watching(watches + w, addWatchedUtxos(watchedUtxos, w), block2tx, nextTick)
        case Ignore => ()
      }

    case PublishAsap(tx) =>
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeouts = Scripts.csvTimeouts(tx)
      if (csvTimeouts.nonEmpty) {
        // watcher supports txs with multiple csv-delayed inputs: we watch all delayed parents and try to publish every
        // time a parent's relative delays are satisfied, so we will eventually succeed.
        csvTimeouts.foreach { case (parentTxId, csvTimeout) =>
          log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parentTxId=$parentTxId tx={}", tx)
          val parentPublicKeyScript = Script.write(Script.pay2wsh(tx.txIn.find(_.outPoint.txid == parentTxId).get.witness.stack.last))
          self ! WatchConfirmed(self, parentTxId, ByteVector.view(parentPublicKeyScript), minDepth = csvTimeout, BITCOIN_PARENT_TX_CONFIRMED(tx))
        }
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]) :+ tx)
        context become watching(watches, watchedUtxos, block2tx1, nextTick)
      } else publish(tx)

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), _, _, _) =>
      log.info(s"parent tx of txid=${tx.txid} has been confirmed")
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]) :+ tx)
        context become watching(watches, watchedUtxos, block2tx1, nextTick)
      } else publish(tx)

    case ValidateRequest(ann) => client.validate(ann).pipeTo(sender)

    case GetTxWithMeta(txid) => client.getTransactionMeta(txid).pipeTo(sender)

    case Terminated(actor) =>
      // we remove watches associated to dead actor
      val deprecatedWatches = watches.filter(_.replyTo == actor)
      val watchedUtxos1 = deprecatedWatches.foldLeft(watchedUtxos) { case (m, w) => removeWatchedUtxos(m, w) }
      context.become(watching(watches -- deprecatedWatches, watchedUtxos1, block2tx, nextTick))

    case Symbol("watches") => sender ! watches

  }

  // NOTE: we use a single thread to publish transactions so that it preserves order.
  // CHANGING THIS WILL RESULT IN CONCURRENCY ISSUES WHILE PUBLISHING PARENT AND CHILD TXS
  val singleThreadExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  def publish(tx: Transaction, isRetry: Boolean = false): Unit = {
    log.info(s"publishing tx (isRetry=$isRetry): txid=${tx.txid} tx={}", tx)
    client.publishTransaction(tx)(singleThreadExecutionContext).recover {
      case t: Throwable if t.getMessage.contains("(code: -25)") && !isRetry => // we retry only once
        import akka.pattern.after
        after(3 seconds, context.system.scheduler)(Future.successful({})).map(_ => publish(tx, isRetry = true))
      case t: Throwable => log.error("cannot publish tx: reason={} txid={} tx={}", t.getMessage, tx.txid, tx)
    }
  }

  def checkConfirmed(w: WatchConfirmed): Future[Unit] = {
    log.debug("checking confirmations of txid={}", w.txId)
    // NB: this is very inefficient since internally we call `getrawtransaction` three times, but it doesn't really
    // matter because this only happens once, when the watched transaction has reached min_depth
    client.getTxConfirmations(w.txId).flatMap {
      case Some(confirmations) if confirmations >= w.minDepth =>
        client.getTransaction(w.txId).flatMap { tx =>
          client.getTransactionShortId(w.txId).map {
            case (height, index) => self ! TriggerEvent(w, WatchEventConfirmed(w.event, height, index, tx))
          }
        }
    }
  }

  // TODO: move to a separate actor that listens to CurrentBlockCount and manages utxos for RBF
  def checkUtxos(): Future[Unit] = {
    case class Utxo(txId: ByteVector32, amount: MilliBtc, confirmations: Long, safe: Boolean)

    def listUnspent(): Future[Seq[Utxo]] = client.rpcClient.invoke("listunspent", /* minconf */ 0).collect {
      case JArray(values) => values.map(utxo => {
        val JInt(confirmations) = utxo \ "confirmations"
        val JBool(safe) = utxo \ "safe"
        val JDecimal(amount) = utxo \ "amount"
        val JString(txid) = utxo \ "txid"
        Utxo(ByteVector32.fromValidHex(txid), (amount.doubleValue * 1000).millibtc, confirmations.toLong, safe)
      })
    }

    def getUnconfirmedAncestorCount(utxo: Utxo): Future[(ByteVector32, Long)] = client.rpcClient.invoke("getmempoolentry", utxo.txId).map(json => {
      val JInt(ancestorCount) = json \ "ancestorcount"
      (utxo.txId, ancestorCount.toLong)
    })

    def getUnconfirmedAncestorCountMap(utxos: Seq[Utxo]): Future[Map[ByteVector32, Long]] = Future.sequence(utxos.filter(_.confirmations == 0).map(getUnconfirmedAncestorCount)).map(_.toMap)

    def recordUtxos(utxos: Seq[Utxo], ancestorCount: Map[ByteVector32, Long]): Unit = {
      val filteredByStatus = Seq(
        (Monitoring.Tags.UtxoStatuses.Confirmed, utxos.filter(utxo => utxo.confirmations > 0)),
        // We cannot create chains of unconfirmed transactions with more than 25 elements, so we ignore such utxos.
        (Monitoring.Tags.UtxoStatuses.Unconfirmed, utxos.filter(utxo => utxo.confirmations == 0 && ancestorCount.getOrElse(utxo.txId, 1L) < 25)),
        (Monitoring.Tags.UtxoStatuses.Safe, utxos.filter(utxo => utxo.safe)),
        (Monitoring.Tags.UtxoStatuses.Unsafe, utxos.filter(utxo => !utxo.safe)),
      )
      filteredByStatus.foreach {
        case (status, filteredUtxos) =>
          val amount = filteredUtxos.map(_.amount.toDouble).sum
          log.info(s"we have ${filteredUtxos.length} $status utxos ($amount mBTC)")
          Monitoring.Metrics.UtxoCount.withTag(Monitoring.Tags.UtxoStatus, status).update(filteredUtxos.length)
          Monitoring.Metrics.BitcoinBalance.withTag(Monitoring.Tags.UtxoStatus, status).update(amount)
      }
    }

    (for {
      utxos <- listUnspent()
      ancestorCount <- getUnconfirmedAncestorCountMap(utxos)
    } yield recordUtxos(utxos, ancestorCount)).recover {
      case ex => log.warning(s"could not check utxos: $ex")
    }
  }

}

object ZmqWatcher {

  def props(chainHash: ByteVector32, blockCount: AtomicLong, client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) = Props(new ZmqWatcher(chainHash, blockCount, client)(ec))

  case object TickNewBlock

  private def utxo(w: Watch): Option[OutPoint] =
    w match {
      case w: WatchSpent => Some(new OutPoint(w.txId.reversed(), w.outputIndex))
      case w: WatchSpentBasic => Some(new OutPoint(w.txId.reversed(), w.outputIndex))
      case _ => None
    }

  /**
   * The resulting map allows checking spent txes in constant time wrt number of watchers
   */
  def addWatchedUtxos(m: Map[OutPoint, Set[Watch]], w: Watch): Map[OutPoint, Set[Watch]] = {
    utxo(w) match {
      case Some(utxo) => m.get(utxo) match {
        case Some(watches) => m + (utxo -> (watches + w))
        case None => m + (utxo -> Set(w))
      }
      case None => m
    }
  }

  def removeWatchedUtxos(m: Map[OutPoint, Set[Watch]], w: Watch): Map[OutPoint, Set[Watch]] = {
    utxo(w) match {
      case Some(utxo) => m.get(utxo) match {
        case Some(watches) if watches - w == Set.empty => m - utxo
        case Some(watches) => m + (utxo -> (watches - w))
        case None => m
      }
      case None => m
    }
  }

}
