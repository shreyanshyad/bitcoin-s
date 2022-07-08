package org.bitcoins.node

import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.node.models.Peer
import org.bitcoins.server.BitcoinSAppConfig
import org.bitcoins.testkit.BitcoinSTestAppConfig
import org.bitcoins.testkit.node.fixture.NeutrinoNodeConnectedWithBitcoinds
import org.bitcoins.testkit.node.{NodeTestUtil, NodeUnitTest}
import org.bitcoins.testkit.rpc.BitcoindRpcTestUtil
import org.bitcoins.testkit.tor.CachedTor
import org.bitcoins.testkit.util.TorUtil
import org.scalatest.{FutureOutcome, Outcome}

import scala.concurrent.{Await, Future}

/** Neutrino node tests that require changing the state of bitcoind instance */
class NeutrinoNodeWithUncachedBitcoindTest extends NodeUnitTest with CachedTor {

  lazy val bitcoindsF =
    BitcoindRpcTestUtil.createNodePair().map(p => Vector(p._1, p._2))

  lazy val bitcoinPeersF: Future[Vector[Peer]] = {
    bitcoindsF.flatMap { bitcoinds =>
      val peersF = bitcoinds.map(NodeTestUtil.getBitcoindPeer)
      Future.sequence(peersF)
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

  //was a faulty test
  it must "switch to different peer and sync if current is unavailable" in {
    nodeConnectedWithBitcoinds =>
      val node = nodeConnectedWithBitcoinds.node
      val bitcoinds = nodeConnectedWithBitcoinds.bitcoinds
      val peerManager = node.peerManager
      def peers = peerManager.peers

      for {
        _ <- AsyncUtil.retryUntilSatisfied(peers.size == 2)
        bitcoindPeers <- bitcoinPeersF
        _ = node.updateDataMessageHandler(
          node.getDataMessageHandler.copy(syncPeer = Some(bitcoindPeers(0)))(
            executionContext,
            node.nodeAppConfig,
            node.chainAppConfig))
        _ = peerManager.peerData(bitcoindPeers(0)).client.close()
        _ <- NodeTestUtil.awaitAllSync(node, bitcoinds(1))
        newSyncPeer = node.getDataMessageHandler.syncPeer.get
        peer2 = bitcoindPeers(1)
      } yield {
        assert(newSyncPeer == peer2)
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
