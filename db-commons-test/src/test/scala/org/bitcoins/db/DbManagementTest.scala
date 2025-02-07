package org.bitcoins.db

import com.typesafe.config.Config
import org.bitcoins.chain.config.ChainAppConfig
import org.bitcoins.chain.db.ChainDbManagement
import org.bitcoins.db.DatabaseDriver._
import org.bitcoins.dlc.oracle.config.DLCOracleAppConfig
import org.bitcoins.dlc.wallet.{DLCAppConfig, DLCDbManagement}
import org.bitcoins.node.config.NodeAppConfig
import org.bitcoins.node.db.NodeDbManagement
import org.bitcoins.testkit.BitcoinSTestAppConfig.ProjectType
import org.bitcoins.testkit.util.BitcoinSAsyncTest
import org.bitcoins.testkit.{BitcoinSTestAppConfig, EmbeddedPg}
import org.bitcoins.wallet.config.WalletAppConfig
import org.bitcoins.wallet.db.WalletDbManagement

import scala.concurrent.ExecutionContext

class DbManagementTest extends BitcoinSAsyncTest with EmbeddedPg {

  def dbConfig(project: ProjectType): Config = {
    BitcoinSTestAppConfig.configWithEmbeddedDb(Some(project), pgUrl)
  }

  def createChainDbManagement(
      chainAppConfig: ChainAppConfig): ChainDbManagement =
    new ChainDbManagement with JdbcProfileComponent[ChainAppConfig] {
      override val ec: ExecutionContext = system.dispatcher

      override def appConfig: ChainAppConfig = chainAppConfig
    }

  def createDLCDbManagement(dlcAppConfig: DLCAppConfig): DLCDbManagement =
    new DLCDbManagement with JdbcProfileComponent[DLCAppConfig] {
      override val ec: ExecutionContext = system.dispatcher

      override def appConfig: DLCAppConfig = dlcAppConfig
    }

  def createWalletDbManagement(
      walletAppConfig: WalletAppConfig): WalletDbManagement =
    new WalletDbManagement with JdbcProfileComponent[WalletAppConfig] {
      override val ec: ExecutionContext = system.dispatcher

      override def appConfig: WalletAppConfig = walletAppConfig
    }

  def createNodeDbManagement(nodeAppConfig: NodeAppConfig): NodeDbManagement =
    new NodeDbManagement with JdbcProfileComponent[NodeAppConfig] {
      override val ec: ExecutionContext = system.dispatcher

      override def appConfig: NodeAppConfig = nodeAppConfig
    }

  it must "run migrations for chain db" in {
    val chainAppConfig = ChainAppConfig(BitcoinSTestAppConfig.tmpDir(),
                                        Vector(dbConfig(ProjectType.Chain)))
    val chainDbManagement = createChainDbManagement(chainAppConfig)
    val result = chainDbManagement.migrate()
    chainAppConfig.driver match {
      case SQLite =>
        val expected = 7
        assert(result.migrationsExecuted == expected)
        val flywayInfo = chainDbManagement.info()
        assert(flywayInfo.applied().length == expected)
        assert(flywayInfo.pending().length == 0)
      case PostgreSQL =>
        val expected = 6
        assert(result.migrationsExecuted == expected)
        val flywayInfo = chainDbManagement.info()
        //+1 for << Flyway Schema Creation >>
        assert(flywayInfo.applied().length == expected + 1)
        assert(flywayInfo.pending().length == 0)
    }

  }

  it must "run migrations for dlc db" in {
    val dlcAppConfig =
      DLCAppConfig(BitcoinSTestAppConfig.tmpDir(),
                   Vector(dbConfig(ProjectType.DLC)))
    val dlcDbManagement = createDLCDbManagement(dlcAppConfig)
    val result = dlcDbManagement.migrate()
    dlcAppConfig.driver match {
      case SQLite =>
        val expected = 8
        assert(result.migrationsExecuted == expected)
        val flywayInfo = dlcAppConfig.info()
        assert(flywayInfo.applied().length == expected)
        assert(flywayInfo.pending().length == 0)
      case PostgreSQL =>
        val expected = 9
        assert(result.migrationsExecuted == expected)
        val flywayInfo = dlcAppConfig.info()

        //+1 for << Flyway Schema Creation >>
        assert(flywayInfo.applied().length == expected + 1)
        assert(flywayInfo.pending().length == 0)
    }
  }

  it must "run migrations for wallet db" in {
    val walletAppConfig = WalletAppConfig(BitcoinSTestAppConfig.tmpDir(),
                                          Vector(dbConfig(ProjectType.Wallet)))
    val walletDbManagement = createWalletDbManagement(walletAppConfig)
    val result = walletDbManagement.migrate()
    walletAppConfig.driver match {
      case SQLite =>
        val expected = 16
        assert(result.migrationsExecuted == expected)
        val flywayInfo = walletDbManagement.info()
        assert(flywayInfo.applied().length == expected)
        assert(flywayInfo.pending().length == 0)
      case PostgreSQL =>
        val expected = 14
        assert(result.migrationsExecuted == expected)
        val flywayInfo = walletDbManagement.info()

        //+1 for << Flyway Schema Creation >>
        assert(flywayInfo.applied().length == expected + 1)
        assert(flywayInfo.pending().length == 0)
    }

  }

  it must "run migrations for node db" in {
    val nodeAppConfig =
      NodeAppConfig(BitcoinSTestAppConfig.tmpDir(),
                    Vector(dbConfig(ProjectType.Node)))
    val nodeDbManagement = createNodeDbManagement(nodeAppConfig)
    val result = nodeDbManagement.migrate()
    nodeAppConfig.driver match {
      case SQLite =>
        val expected = 5
        assert(result.migrationsExecuted == expected)
        val flywayInfo = nodeDbManagement.info()

        assert(flywayInfo.applied().length == expected)
        assert(flywayInfo.pending().length == 0)
      case PostgreSQL =>
        val expected = 5
        assert(result.migrationsExecuted == expected)
        val flywayInfo = nodeDbManagement.info()

        //+1 for << Flyway Schema Creation >>
        assert(flywayInfo.applied().length == expected + 1)
        assert(flywayInfo.pending().length == 0)
    }
  }

  it must "run migrations for oracle db" in {
    val oracleAppConfig =
      DLCOracleAppConfig(BitcoinSTestAppConfig.tmpDir(),
                         Vector(dbConfig(ProjectType.Oracle)))
    val result = oracleAppConfig.migrate()
    oracleAppConfig.driver match {
      case SQLite =>
        val expected = 6
        assert(result.migrationsExecuted == expected)
        val flywayInfo = oracleAppConfig.info()

        assert(flywayInfo.applied().length == expected)
        assert(flywayInfo.pending().length == 0)
      case PostgreSQL =>
        val expected = 6
        assert(result.migrationsExecuted == expected)
        val flywayInfo = oracleAppConfig.info()

        //+1 for << Flyway Schema Creation >>
        assert(flywayInfo.applied().length == expected + 1)
        assert(flywayInfo.pending().length == 0)
    }
  }
}
