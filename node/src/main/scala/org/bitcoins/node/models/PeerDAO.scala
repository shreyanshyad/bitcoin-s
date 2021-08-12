package org.bitcoins.node.models

import org.bitcoins.db.{CRUD, SlickUtil}
import org.bitcoins.node.config.NodeAppConfig
import slick.lifted.ProvenShape

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

case class PeerDB(
    address: String,
    lastConnected: Instant
)

case class PeerDAO()(implicit ec: ExecutionContext, appConfig: NodeAppConfig)
    extends CRUD[PeerDB, String]
    with SlickUtil[PeerDB, String] {

  import profile.api._

  override val table: TableQuery[PeerTable] =
    TableQuery[PeerTable]

  override def createAll(ts: Vector[PeerDB]): Future[Vector[PeerDB]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[String]): Query[PeerTable, PeerDB, Seq] = {
    table.filter(_.address.inSet(ids))
  }

  override protected def findAll(
      ts: Vector[PeerDB]): Query[Table[_], PeerDB, Seq] = findByPrimaryKeys(
    ts.map(_.address))

  class PeerTable(tag: Tag) extends Table[PeerDB](tag, schemaName, "peers") {

    def address: Rep[String] = column("address", O.PrimaryKey)

    def lastConnected: Rep[Instant] = column("last_connected")

    def * : ProvenShape[PeerDB] =
      (address, lastConnected).<>(PeerDB.tupled, PeerDB.unapply)
  }
}
