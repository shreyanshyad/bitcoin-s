package org.bitcoins.node

import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.core.p2p.HeadersMessage
import org.bitcoins.node.models.Peer
import org.bitcoins.node.networking.peer.DataMessageHandlerState
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.server.BitcoinSAppConfig
import org.bitcoins.testkit.BitcoinSTestAppConfig
import org.bitcoins.testkit.node.fixture.NeutrinoNodeConnectedWithBitcoinds
import org.bitcoins.testkit.node.{NodeTestUtil, NodeUnitTest}
import org.bitcoins.testkit.rpc.BitcoindRpcTestUtil
import org.bitcoins.testkit.tor.CachedTor
import org.bitcoins.testkit.util.{AkkaUtil, TorUtil}
import org.scalatest.{FutureOutcome, Outcome}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

/** Neutrino node tests that require changing the state of bitcoind instance */
class NeutrinoNodeWithUncachedBitcoindTest extends NodeUnitTest with CachedTor {

  lazy val bitcoindsF =
    BitcoindRpcTestUtil
      .createUnconnectedNodePairWithBlocks()
      .map(p => Vector(p._1, p._2))

  lazy val bitcoinPeersF: Future[Vector[Peer]] = {
    bitcoindsF.flatMap { bitcoinds =>
      val peersF = bitcoinds.map(NodeTestUtil.getBitcoindPeer)
      Future.sequence(peersF)
    }
  }

  def bestChainBitcoind: Future[BitcoindRpcClient] = {
    for {
      bitcoinds <- bitcoindsF
      h1 <- bitcoinds(0).getBestHashBlockHeight()
      h2 <- bitcoinds(1).getBestHashBlockHeight()
    } yield {
      if (h1 > h2) bitcoinds(0)
      else bitcoinds(1)
    }
  }

  override protected def getFreshConfig: BitcoinSAppConfig = {
    BitcoinSTestAppConfig.getMultiPeerNeutrinoWithEmbeddedDbTestConfig(pgUrl)
  }

  override type FixtureParam = NeutrinoNodeConnectedWithBitcoinds

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    val torClientF = if (TorUtil.torEnabled) torF else Future.unit

    val outcomeF: Future[Outcome] = for {
      _ <- torClientF
      bitcoinds <- bitcoindsF
      outcome = withUnsyncedNeutrinoNodeConnectedToBitcoinds(test, bitcoinds)(
        system,
        getFreshConfig)
      f <- outcome.toFuture
    } yield f
    new FutureOutcome(outcomeF)
  }

  behavior of "NeutrinoNode"

  it must "switch to different peer and sync if current is unavailable" in {
    nodeConnectedWithBitcoinds =>
      val node = nodeConnectedWithBitcoinds.node
      val bitcoinds = nodeConnectedWithBitcoinds.bitcoinds
      val peerManager = node.peerManager
      def peers = peerManager.peers
      for {
        bitcoindPeers <- bitcoinPeersF
        zipped = bitcoinds.zip(bitcoindPeers)
        _ <- AsyncUtil.retryUntilSatisfied(peers.size == 2,
                                           interval = 1.second,
                                           maxTries = 10)
        _ <- node.sync()
        sync1 = zipped.find(_._2 == node.getDataMessageHandler.syncPeer.get).get
        h1 <- sync1._1.getBestHashBlockHeight()
        uri1 <- NodeTestUtil.getNodeUri(sync1._1)
        _ <- sync1._1.disconnectNode(uri1)
        // generating new blocks from the other bitcoind instance
        other = bitcoinds.filterNot(_ == sync1._1).head
        _ <- other.getNewAddress.flatMap(other.generateToAddress(10, _))
        _ <- AkkaUtil.nonBlockingSleep(2.seconds)
        sync2 = zipped.find(_._2 == node.getDataMessageHandler.syncPeer.get).get
        _ <- NodeTestUtil.awaitAllSync(node, sync2._1)
        h2 <- sync2._1.getBestHashBlockHeight()
      } yield {
        assert(sync1._2 != sync2._2 && h2 - h1 == 10)
      }
  }
  //note: now bitcoinds are unconnected and not in sync now

  it must "have the best header chain post sync from all peers" in {
    nodeConnectedWithBitcoinds =>
      val node = nodeConnectedWithBitcoinds.node
      val bitcoinds = nodeConnectedWithBitcoinds.bitcoinds
      val peerManager = node.peerManager

      def peers = peerManager.peers

      for {
        _ <- AsyncUtil.retryUntilSatisfied(peers.size == 2)
        //so now peer with better height would not be synced from first would be deferred
        h1 <- bitcoinds(0).getBestHashBlockHeight()
        h2 <- bitcoinds(1).getBestHashBlockHeight()

        //out of sync by 10 blocks
        _ = assert(Math.abs(h2 - h1) == 10)

        _ <- node.sync()
        betterChain = if (h1 > h2) bitcoinds(0) else bitcoinds(1)
        _ <- NodeTestUtil.awaitSync(node, betterChain)
      } yield {
        succeed
      }
  }

  it must "re-query in case invalid headers are sent" in {
    nodeConnectedWithBitcoinds =>
      val node = nodeConnectedWithBitcoinds.node

      for {
        _ <- AsyncUtil.retryUntilSatisfied(node.peerManager.peers.size == 2)
        peers <- bitcoinPeersF
        peer = peers.head
        _ = node.updateDataMessageHandler(
          node.getDataMessageHandler.copy(
            syncPeer = Some(peer),
            state = DataMessageHandlerState.HeaderSync)(executionContext,
                                                        node.nodeAppConfig,
                                                        node.chainAppConfig))
        invalidHeader = node.chainAppConfig.chain.genesisBlock.blockHeader
        invalidHeaderMessage = HeadersMessage(headers = Vector(invalidHeader))
        sender = node.peerManager.peerData(peer).peerMessageSender
        _ <- node.getDataMessageHandler.handleDataPayload(invalidHeaderMessage,
                                                          sender,
                                                          peer)
        bestChain <- bestChainBitcoind
        _ <- NodeTestUtil.awaitSync(node, bestChain)
      } yield {
        succeed
      }
  }

  it must "must disconnect a peer that keeps sending invalid headers" in {
    nodeConnectedWithBitcoinds =>
      val node = nodeConnectedWithBitcoinds.node
      val peerManager = node.peerManager

      def sendInvalidHeaders(peer: Peer): Future[Unit] = {
        val invalidHeader = node.chainAppConfig.chain.genesisBlock.blockHeader
        val invalidHeaderMessage =
          HeadersMessage(headers = Vector(invalidHeader))
        val sender = node.peerManager.peerData(peer).peerMessageSender

        val sendFs =
          1.to(node.nodeConfig.maxInvalidResponsesAllowed + 1).map { _ =>
            node.getDataMessageHandler.handleDataPayload(invalidHeaderMessage,
                                                         sender,
                                                         peer)
          }

        Future.sequence(sendFs).map(_ => ())
      }

      for {
        _ <- AsyncUtil.retryUntilSatisfied(peerManager.peers.size == 2)
        peers <- bitcoinPeersF
        peer = peers(0)
        _ <- node.peerManager.isConnected(peer).map(assert(_))
        _ = node.updateDataMessageHandler(
          node.getDataMessageHandler.copy(
            syncPeer = Some(peer),
            state = DataMessageHandlerState.HeaderSync)(executionContext,
                                                        node.nodeAppConfig,
                                                        node.chainAppConfig))
        _ <- sendInvalidHeaders(peer)
        _ <- AsyncUtil.retryUntilSatisfiedF(() =>
          node.peerManager.isDisconnected(peer))
      } yield {
        succeed
      }
  }

  override def afterAll(): Unit = {
    val stopF = for {
      bitcoinds <- bitcoindsF
      _ <- BitcoindRpcTestUtil.stopServers(bitcoinds)
    } yield ()
    Await.result(stopF, duration)
    super.afterAll()
  }
}
