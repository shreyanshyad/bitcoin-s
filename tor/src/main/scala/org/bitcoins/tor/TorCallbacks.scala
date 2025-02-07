package org.bitcoins.tor

import grizzled.slf4j.Logging
import org.bitcoins.core.api.callback.{CallbackFactory, ModuleCallbacks}
import org.bitcoins.core.api.{Callback, CallbackHandler}

import scala.concurrent.{ExecutionContext, Future}

trait OnTorStarted extends Callback[Unit]

trait TorCallbacks extends ModuleCallbacks[TorCallbacks] with Logging {
  def onTorStarted: CallbackHandler[Unit, OnTorStarted]

  def executeOnTorStarted()(implicit ec: ExecutionContext): Future[Unit] = {
    onTorStarted.execute(
      (),
      (err: Throwable) =>
        logger.error(s"${onTorStarted.name} Callback failed with", err))
  }

  def +(other: TorCallbacks): TorCallbacks
}

object TorCallbacks extends CallbackFactory[TorCallbacks] {

  private case class TorCallbacksImpl(
      onTorStarted: CallbackHandler[Unit, OnTorStarted]
  ) extends TorCallbacks {

    override def +(other: TorCallbacks): TorCallbacks = {
      copy(onTorStarted = onTorStarted ++ other.onTorStarted)
    }
  }

  override val empty: TorCallbacks = apply(Vector.empty)

  def apply(onTorStarted: OnTorStarted): TorCallbacks = {
    apply(Vector(onTorStarted))
  }

  def apply(onTorStarted: Vector[OnTorStarted]): TorCallbacks = {
    val handler =
      CallbackHandler[Unit, OnTorStarted]("onTorStarted", onTorStarted)
    TorCallbacksImpl(handler)
  }
}
