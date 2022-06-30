package org.bitcoins.node.networking

import org.bitcoins.node.models.Peer
import org.bitcoins.testkit.node.{CachedBitcoinSAppConfig, NodeTestUtil}
import org.bitcoins.testkit.rpc.BitcoindRpcTestUtil
import org.bitcoins.testkit.util.BitcoindRpcTest

import scala.concurrent.Future

class MultiRpcTest extends BitcoindRpcTest with CachedBitcoinSAppConfig {

  lazy val bitcoindRpcF =
    BitcoindRpcTestUtil.startedBitcoindRpcClient(clientAccum = clientAccum)

  lazy val bitcoindPeerF: Future[Peer] =
    bitcoindRpcF.flatMap(b => NodeTestUtil.getBitcoindPeer(b))

  lazy val bitcoindRpc2F =
    BitcoindRpcTestUtil.startedBitcoindRpcClient(clientAccum = clientAccum)

  lazy val bitcoindPeer2F: Future[Peer] =
    bitcoindRpc2F.flatMap(b => NodeTestUtil.getBitcoindPeer(b))

  behavior of "MultiRpcTest"

  it must "create multiple different rpc instances" in {
    for {
      p1 <- bitcoindPeerF
      p2 <- bitcoindPeer2F
    } yield {
      assert(p1 != p2)
    }
  }

  override def afterAll(): Unit = {
    super[CachedBitcoinSAppConfig].afterAll()
    super[BitcoindRpcTest].afterAll()
  }
}
