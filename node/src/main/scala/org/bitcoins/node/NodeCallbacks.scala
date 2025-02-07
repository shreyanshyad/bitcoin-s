package org.bitcoins.node

import grizzled.slf4j.Logging
import org.bitcoins.core.api.callback.{CallbackFactory, ModuleCallbacks}
import org.bitcoins.core.api.{Callback, Callback2, CallbackHandler}
import org.bitcoins.core.gcs.GolombFilter
import org.bitcoins.core.protocol.blockchain.{Block, BlockHeader, MerkleBlock}
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.crypto.DoubleSha256Digest

import scala.concurrent.{ExecutionContext, Future}

/** Callbacks for responding to events in the node.
  * The appropriate callback is executed whenever the node receives
  * a `getdata` message matching it.
  */
trait NodeCallbacks extends ModuleCallbacks[NodeCallbacks] with Logging {

  def onCompactFiltersReceived: CallbackHandler[
    Vector[(DoubleSha256Digest, GolombFilter)],
    OnCompactFiltersReceived]

  def onTxReceived: CallbackHandler[Transaction, OnTxReceived]

  def onBlockReceived: CallbackHandler[Block, OnBlockReceived]

  def onMerkleBlockReceived: CallbackHandler[
    (MerkleBlock, Vector[Transaction]),
    OnMerkleBlockReceived]

  def onBlockHeadersReceived: CallbackHandler[
    Vector[BlockHeader],
    OnBlockHeadersReceived]

  override def +(other: NodeCallbacks): NodeCallbacks

  def executeOnTxReceivedCallbacks(tx: Transaction)(implicit
      ec: ExecutionContext): Future[Unit] = {
    onTxReceived.execute(
      tx,
      (err: Throwable) =>
        logger.error(s"${onTxReceived.name} Callback failed with error: ", err))
  }

  def executeOnBlockReceivedCallbacks(block: Block)(implicit
      ec: ExecutionContext): Future[Unit] = {
    onBlockReceived.execute(
      block,
      (err: Throwable) =>
        logger.error(s"${onBlockReceived.name} Callback failed with error: ",
                     err))
  }

  def executeOnMerkleBlockReceivedCallbacks(
      merkleBlock: MerkleBlock,
      txs: Vector[Transaction])(implicit ec: ExecutionContext): Future[Unit] = {
    onMerkleBlockReceived.execute(
      (merkleBlock, txs),
      (err: Throwable) =>
        logger.error(
          s"${onMerkleBlockReceived.name} Callback failed with error: ",
          err))
  }

  def executeOnCompactFiltersReceivedCallbacks(
      blockFilters: Vector[(DoubleSha256Digest, GolombFilter)])(implicit
      ec: ExecutionContext): Future[Unit] = {
    onCompactFiltersReceived.execute(
      blockFilters,
      (err: Throwable) =>
        logger.error(
          s"${onCompactFiltersReceived.name} Callback failed with error: ",
          err))
  }

  def executeOnBlockHeadersReceivedCallbacks(headers: Vector[BlockHeader])(
      implicit ec: ExecutionContext): Future[Unit] = {
    onBlockHeadersReceived.execute(
      headers,
      (err: Throwable) =>
        logger.error(
          s"${onBlockHeadersReceived.name} Callback failed with error: ",
          err))
  }
}

/** Callback for handling a received block */
trait OnBlockReceived extends Callback[Block]

/** Callback for handling a received Merkle block with its corresponding TXs */
trait OnMerkleBlockReceived extends Callback2[MerkleBlock, Vector[Transaction]]

/** Callback for handling a received transaction */
trait OnTxReceived extends Callback[Transaction]

/** Callback for handling a received compact block filter */
trait OnCompactFiltersReceived
    extends Callback[Vector[(DoubleSha256Digest, GolombFilter)]]

/** Callback for handling a received block header */
trait OnBlockHeadersReceived extends Callback[Vector[BlockHeader]]

object NodeCallbacks extends CallbackFactory[NodeCallbacks] {

  // Use Impl pattern here to enforce the correct names on the CallbackHandlers
  private case class NodeCallbacksImpl(
      onCompactFiltersReceived: CallbackHandler[
        Vector[(DoubleSha256Digest, GolombFilter)],
        OnCompactFiltersReceived],
      onTxReceived: CallbackHandler[Transaction, OnTxReceived],
      onBlockReceived: CallbackHandler[Block, OnBlockReceived],
      onMerkleBlockReceived: CallbackHandler[
        (MerkleBlock, Vector[Transaction]),
        OnMerkleBlockReceived],
      onBlockHeadersReceived: CallbackHandler[
        Vector[BlockHeader],
        OnBlockHeadersReceived]
  ) extends NodeCallbacks {

    override def +(other: NodeCallbacks): NodeCallbacks =
      copy(
        onCompactFiltersReceived =
          onCompactFiltersReceived ++ other.onCompactFiltersReceived,
        onTxReceived = onTxReceived ++ other.onTxReceived,
        onBlockReceived = onBlockReceived ++ other.onBlockReceived,
        onMerkleBlockReceived =
          onMerkleBlockReceived ++ other.onMerkleBlockReceived,
        onBlockHeadersReceived =
          onBlockHeadersReceived ++ other.onBlockHeadersReceived
      )
  }

  /** Constructs a set of callbacks that only acts on TX received */
  def onTxReceived(f: OnTxReceived): NodeCallbacks =
    NodeCallbacks(onTxReceived = Vector(f))

  /** Constructs a set of callbacks that only acts on block received */
  def onBlockReceived(f: OnBlockReceived): NodeCallbacks =
    NodeCallbacks(onBlockReceived = Vector(f))

  /** Constructs a set of callbacks that only acts on merkle block received */
  def onMerkleBlockReceived(f: OnMerkleBlockReceived): NodeCallbacks =
    NodeCallbacks(onMerkleBlockReceived = Vector(f))

  /** Constructs a set of callbacks that only acts on compact filter received */
  def onCompactFilterReceived(f: OnCompactFiltersReceived): NodeCallbacks =
    NodeCallbacks(onCompactFiltersReceived = Vector(f))

  /** Constructs a set of callbacks that only acts on block headers received */
  def onBlockHeadersReceived(f: OnBlockHeadersReceived): NodeCallbacks =
    NodeCallbacks(onBlockHeadersReceived = Vector(f))

  /** Empty callbacks that does nothing with the received data */
  override val empty: NodeCallbacks =
    NodeCallbacks(Vector.empty,
                  Vector.empty,
                  Vector.empty,
                  Vector.empty,
                  Vector.empty)

  def apply(
      onCompactFiltersReceived: Vector[OnCompactFiltersReceived] = Vector.empty,
      onTxReceived: Vector[OnTxReceived] = Vector.empty,
      onBlockReceived: Vector[OnBlockReceived] = Vector.empty,
      onMerkleBlockReceived: Vector[OnMerkleBlockReceived] = Vector.empty,
      onBlockHeadersReceived: Vector[OnBlockHeadersReceived] =
        Vector.empty): NodeCallbacks = {
    NodeCallbacksImpl(
      onCompactFiltersReceived =
        CallbackHandler[Vector[(DoubleSha256Digest, GolombFilter)],
                        OnCompactFiltersReceived]("onCompactFilterReceived",
                                                  onCompactFiltersReceived),
      onTxReceived = CallbackHandler[Transaction, OnTxReceived]("onTxReceived",
                                                                onTxReceived),
      onBlockReceived =
        CallbackHandler[Block, OnBlockReceived]("onBlockReceived",
                                                onBlockReceived),
      onMerkleBlockReceived =
        CallbackHandler[(MerkleBlock, Vector[Transaction]),
                        OnMerkleBlockReceived]("onCompactFilterReceived",
                                               onMerkleBlockReceived),
      onBlockHeadersReceived =
        CallbackHandler[Vector[BlockHeader], OnBlockHeadersReceived](
          "onCompactFilterReceived",
          onBlockHeadersReceived)
    )
  }
}
