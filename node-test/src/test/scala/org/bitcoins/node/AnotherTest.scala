package org.bitcoins.node

import org.bitcoins.server.BitcoinSAppConfig
import org.bitcoins.testkit.BitcoinSTestAppConfig
import org.bitcoins.testkit.node.NodeTestWithCachedBitcoindNewest
import org.bitcoins.testkit.node.fixture.NeutrinoNodeConnectedWithBitcoind
import org.bitcoins.testkit.util.TorUtil
import org.scalatest.{FutureOutcome, Outcome}

import scala.concurrent.Future

class AnotherTest extends NodeTestWithCachedBitcoindNewest {

  /** Wallet config with data directory set to user temp directory */
  override protected def getFreshConfig: BitcoinSAppConfig =
    BitcoinSTestAppConfig.getNeutrinoWithEmbeddedDbTestConfig(pgUrl)

  override type FixtureParam = NeutrinoNodeConnectedWithBitcoind

  def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    val torClientF = if (TorUtil.torEnabled) torF else Future.unit

    val outcome: Future[Outcome] = for {
      _ <- torClientF
      bitcoind <- cachedBitcoindWithFundsF
      outcome = withNeutrinoNodeConnectedToBitcoindCached(test, bitcoind)(
        system,
        getFreshConfig)
      f <- outcome.toFuture
    } yield f
    new FutureOutcome(outcome)
  }

  it must "be nice" in { _ =>
    succeed
  }

  it must "be nice1" in { _ =>
    succeed
  }

  it must "be nice2" in { _ =>
    succeed
  }

  it must "be nice3" in { _ =>
    succeed
  }

  it must "be nice4" in { _ =>
    succeed
  }

  it must "be nice5" in { _ =>
    succeed
  }

  it must "be nice6" in { _ =>
    succeed
  }

  it must "be nice7" in { _ =>
    succeed
  }

  it must "be nice8" in { _ =>
    succeed
  }

  it must "be nice9" in { _ =>
    succeed
  }

  it must "be nice10" in { _ =>
    succeed
  }

  it must "be nice11" in { _ =>
    succeed
  }

  it must "be nice12" in { _ =>
    succeed
  }

  it must "be nice13" in { _ =>
    succeed
  }

  it must "be nice14" in { _ =>
    succeed
  }

  it must "be nice15" in { _ =>
    succeed
  }

  it must "be nice16" in { _ =>
    succeed
  }

  it must "be nice17" in { _ =>
    succeed
  }

  it must "be nice18" in { _ =>
    succeed
  }

  it must "be nice19" in { _ =>
    succeed
  }

  it must "be nice20" in { _ =>
    succeed
  }
}
