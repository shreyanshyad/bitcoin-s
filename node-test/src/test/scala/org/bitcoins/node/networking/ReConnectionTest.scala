package org.bitcoins.node.networking

import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.asyncutil.AsyncUtil.RpcRetryException
import org.bitcoins.node.models.Peer
import org.bitcoins.node.networking.peer.PeerHandler
import org.bitcoins.testkit.node.{
  CachedBitcoinSAppConfig,
  NodeTestUtil,
  NodeUnitTest
}
import org.bitcoins.testkit.rpc.BitcoindRpcTestUtil
import org.bitcoins.testkit.util.{AkkaUtil, BitcoindRpcTest}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class ReConnectionTest extends BitcoindRpcTest with CachedBitcoinSAppConfig {

  lazy val bitcoindRpcF =
    BitcoindRpcTestUtil.startedBitcoindRpcClient(clientAccum = clientAccum)

  lazy val bitcoindPeerF: Future[Peer] =
    bitcoindRpcF.flatMap(b => NodeTestUtil.getBitcoindPeer(b))

  behavior of "ReConnectionTest"

  it must "attempt to reconnect if max connections are full" in {
    val peerHandlerF: Future[PeerHandler] = for {
      _ <- cachedConfig.start()
      peer <- bitcoindPeerF.flatMap(p => NodeUnitTest.buildPeerHandler(p, None))
    } yield peer

    val connectedF = for {
      peerHandler <- peerHandlerF
      bitcoindRpc <- bitcoindRpcF
      peer <- bitcoindPeerF

      _ = peerHandler.peerMsgSender.connect()
      _ = logger.info(s"RECONNECTION TEST: $peer")
      _ = logger.info("RECONNECTION TEST: CONNECTED 1")
      _ <- AsyncUtil
        .retryUntilSatisfiedF(() => peerHandler.p2pClient.isConnected())
        .recover { case _: RpcRetryException =>
          //expect this to fail, we cannot connect
          //because maxconnections=0
          ()
        }
      _ = logger.info("RECONNECTION TEST: CONNECTED 1 DONE")
      peerInfo <- bitcoindRpc.getPeerInfo
      _ = logger.info(s"RECONNECTION TEST : $peerInfo")
      uri = peerInfo.head.networkInfo.addr
      _ <- bitcoindRpc.disconnectNode(uri)
      _ <- AkkaUtil.nonBlockingSleep(2.seconds)
      _ = logger.info(s"RECONNECTION TEST: $uri")
      _ = logger.info("RECONNECTION TEST: CONNECTED 2")
      _ <- AsyncUtil.retryUntilSatisfiedF(
        conditionF = () => peerHandler.p2pClient.isConnected(),
        interval = 500.millis,
        maxTries = 60)
      _ = logger.info("RECONNECTION TEST: CONNECTED 2 DONE")
    } yield succeed

    connectedF
  }

  override def afterAll(): Unit = {
    super[CachedBitcoinSAppConfig].afterAll()
    super[BitcoindRpcTest].afterAll()
  }
}
