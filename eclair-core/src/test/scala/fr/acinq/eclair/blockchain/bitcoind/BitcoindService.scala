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

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKitBase, TestProbe}
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.bitcoin.{Base58, Btc, BtcAmount, MilliBtc, PimpSatoshi, PrivateKey, Satoshi, Transaction}
import fr.acinq.eclair.TestUtils
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.integration.IntegrationSpec
import grizzled.slf4j.Logging
import org.json4s.JsonAST._

import java.io.File
import java.nio.file.Files
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.io.Source

trait BitcoindService extends Logging {
  self: TestKitBase =>

  import BitcoindService._

  import scala.sys.process._

  implicit val system: ActorSystem
  implicit val sttpBackend = OkHttpFutureBackend()

  val defaultWallet: String = "miner"
  val bitcoindPort: Int = TestUtils.availablePort
  val bitcoindRpcPort: Int = TestUtils.availablePort
  val bitcoindZmqBlockPort: Int = TestUtils.availablePort
  val bitcoindZmqTxPort: Int = TestUtils.availablePort

  val INTEGRATION_TMP_DIR: File = TestUtils.newIntegrationTmpDir()
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(TestUtils.BUILD_DIRECTORY, "bitcoin-0.20.1/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = _
  var bitcoinrpcclient: BitcoinJsonRPCClient = _
  var bitcoincli: ActorRef = _

  def startBitcoind(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    if (!Files.exists(new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)) {
      val is = classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf")
      val conf = Source.fromInputStream(is).mkString
        .replace("28333", bitcoindPort.toString)
        .replace("28332", bitcoindRpcPort.toString)
        .replace("28334", bitcoindZmqBlockPort.toString)
        .replace("28335", bitcoindZmqTxPort.toString)
      Files.writeString(new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath, conf)
    }

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = bitcoindRpcPort, wallet = Some(defaultWallet))
    bitcoincli = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case BitcoinReq(method) => bitcoinrpcclient.invoke(method).pipeTo(sender)
        case BitcoinReq(method, params) => bitcoinrpcclient.invoke(method, params).pipeTo(sender)
        case BitcoinReq(method, param1, param2) => bitcoinrpcclient.invoke(method, param1, param2).pipeTo(sender)
      }
    }))
  }

  def stopBitcoind(): Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("stop"))
    sender.expectMsgType[JValue]
    bitcoind.exitValue()
  }

  def restartBitcoind(sender: TestProbe = TestProbe()): Unit = {
    stopBitcoind()
    startBitcoind()
    waitForBitcoindUp(sender)
    sender.send(bitcoincli, BitcoinReq("loadwallet", defaultWallet))
    sender.expectMsgType[JValue]
  }

  private def waitForBitcoindUp(sender: TestProbe): Unit = {
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.expectMsgType[Any](5 second) match {
        case j: JValue => j \ "version" match {
          case JInt(_) => true
          case _ => false
        }
        case _ => false
      }
    }, max = 3 minutes, interval = 2 seconds)
  }

  def waitForBitcoindReady(): Unit = {
    val sender = TestProbe()
    waitForBitcoindUp(sender)
    sender.send(bitcoincli, BitcoinReq("createwallet", defaultWallet))
    sender.expectMsgType[JValue]
    logger.info(s"generating initial blocks to wallet=$defaultWallet...")
    generateBlocks(150)
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getblockcount"))
      val JInt(blockCount) = sender.expectMsgType[JInt](30 seconds)
      blockCount >= 150
    }, max = 3 minutes, interval = 2 second)
  }

  /** Generate blocks to a given address, or to our wallet if no address is provided. */
  def generateBlocks(blockCount: Int, address: Option[String] = None, timeout: FiniteDuration = 10 seconds)(implicit system: ActorSystem): Unit = {
    val sender = TestProbe()
    val addressToUse = address match {
      case Some(addr) => addr
      case None =>
        sender.send(bitcoincli, BitcoinReq("getnewaddress"))
        val JString(address) = sender.expectMsgType[JValue](timeout)
        address
    }
    sender.send(bitcoincli, BitcoinReq("generatetoaddress", blockCount, addressToUse))
    val JArray(blocks) = sender.expectMsgType[JValue](timeout)
    assert(blocks.size == blockCount)
  }

  /** Create a new wallet and returns an RPC client to interact with it. */
  def createWallet(walletName: String, sender: TestProbe = TestProbe()): BitcoinJsonRPCClient = {
    sender.send(bitcoincli, BitcoinReq("createwallet", walletName))
    sender.expectMsgType[JValue]
    new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = bitcoindRpcPort, wallet = Some(walletName))
  }

  def getNewAddress(sender: TestProbe = TestProbe(), rpcClient: BitcoinJsonRPCClient = bitcoinrpcclient): String = {
    rpcClient.invoke("getnewaddress").pipeTo(sender.ref)
    val JString(address) = sender.expectMsgType[JValue]
    address
  }

  /** Dump the private key associated with the given address. */
  def dumpPrivateKey(address: String, sender: TestProbe = TestProbe(), rpcClient: BitcoinJsonRPCClient = bitcoinrpcclient): PrivateKey = {
    rpcClient.invoke("dumpprivkey", address).pipeTo(sender.ref)
    val JString(wif) = sender.expectMsgType[JValue]
    val priv = PrivateKey.fromBase58(wif, Base58.Prefix.SecretKeyTestnet).getFirst
    priv
  }

  /** Send to a given address, without generating blocks to confirm. */
  def sendToAddress(address: String, amount: BtcAmount, sender: TestProbe = TestProbe(), rpcClient: BitcoinJsonRPCClient = bitcoinrpcclient): Transaction = {
    val amountDecimal = amount match {
      case amount: PimpSatoshi => amount.toBtc.toBigDecimal
      case amount: MilliBtc => amount.toBtc.toBigDecimal
      case amount: Btc => amount.toBigDecimal
    }
    rpcClient.invoke("sendtoaddress", address, amountDecimal).pipeTo(sender.ref)
    val JString(txid) = sender.expectMsgType[JString]
    rpcClient.invoke("getrawtransaction", txid).pipeTo(sender.ref)
    val JString(rawTx) = sender.expectMsgType[JString]
    Transaction.read(rawTx)
  }

}

object BitcoindService {

  case class BitcoinReq(method: String, params: Any*)

}