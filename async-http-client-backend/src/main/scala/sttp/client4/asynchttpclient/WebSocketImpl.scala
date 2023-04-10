package sttp.client4.asynchttpclient

import java.util.concurrent.atomic.AtomicBoolean

import io.netty.util.concurrent.{Future, FutureListener}
import org.asynchttpclient.ws.{WebSocket => AHCWebSocket, WebSocketListener => AHCWebSocketListener}
import sttp.client4.internal.ws.{SimpleQueue, WebSocketEvent}
import sttp.model.{Header, Headers}
import sttp.monad.syntax._
import sttp.monad.{Canceler, MonadAsyncError}
import sttp.ws.{WebSocket, WebSocketClosed, WebSocketFrame}

import scala.collection.JavaConverters.asScalaIteratorConverter

private[asynchttpclient] class WebSocketImpl[F[_]](
    ws: AHCWebSocket,
    queue: SimpleQueue[F, WebSocketEvent],
    _isOpen: AtomicBoolean,
    implicit val monad: MonadAsyncError[F]
) extends WebSocket[F] {

  override def receive(): F[WebSocketFrame] =
    queue.poll.flatMap {
      case WebSocketEvent.Open() => receive()
      case WebSocketEvent.Frame(c: WebSocketFrame.Close) =>
        queue.offer(WebSocketEvent.Error(WebSocketClosed(Some(c))))
        monad.unit(c)
      case e @ WebSocketEvent.Error(t: Exception) =>
        // putting back the error so that subsequent invocations end in an error as well, instead of hanging
        queue.offer(e)
        monad.error(t)
      case WebSocketEvent.Error(t)                 => throw t
      case WebSocketEvent.Frame(f: WebSocketFrame) => monad.unit(f)
    }

  override def send(f: WebSocketFrame, isContinuation: Boolean = false): F[Unit] =
    monad.flatten(monad.eval(f match {
      case WebSocketFrame.Text(payload, finalFragment, rsv) if !isContinuation =>
        fromNettyFuture(ws.sendTextFrame(payload, finalFragment, rsv.getOrElse(0)))
      case WebSocketFrame.Text(payload, finalFragment, rsv) /* if isContinuation */ =>
        fromNettyFuture(ws.sendContinuationFrame(payload, finalFragment, rsv.getOrElse(0)))
      case WebSocketFrame.Binary(payload, finalFragment, rsv) if !isContinuation =>
        fromNettyFuture(ws.sendBinaryFrame(payload, finalFragment, rsv.getOrElse(0)))
      case WebSocketFrame.Binary(payload, finalFragment, rsv) /* if isContinuation */ =>
        fromNettyFuture(ws.sendContinuationFrame(payload, finalFragment, rsv.getOrElse(0)))
      case WebSocketFrame.Ping(payload) => fromNettyFuture(ws.sendPingFrame(payload))
      case WebSocketFrame.Pong(payload) => fromNettyFuture(ws.sendPongFrame(payload))
      case WebSocketFrame.Close(statusCode, reasonText) =>
        val wasOpen = _isOpen.getAndSet(false)
        // making close sequentially idempotent
        if (wasOpen) {
          queue.offer(WebSocketEvent.Error(WebSocketClosed(None)))
          fromNettyFuture(ws.sendCloseFrame(statusCode, reasonText))
        } else ().unit
    }))

  override def upgradeHeaders: Headers =
    Headers(ws.getUpgradeHeaders.iteratorAsString().asScala.map(e => Header(e.getKey, e.getValue)).toList)

  override def isOpen(): F[Boolean] = monad.eval(_isOpen.get())

  private def fromNettyFuture(f: io.netty.util.concurrent.Future[Void]): F[Unit] =
    monad.async { cb =>
      val f2 = f.addListener(new FutureListener[Void] {
        override def operationComplete(future: Future[Void]): Unit =
          if (future.isSuccess) cb(Right(())) else cb(Left(future.cause()))
      })

      Canceler(() => f2.cancel(true))
    }
}

object WebSocketImpl {
  def newCoupledToAHCWebSocket[F[_]](
      ws: AHCWebSocket,
      queue: SimpleQueue[F, WebSocketEvent]
  )(implicit monad: MonadAsyncError[F]): WebSocket[F] = {
    val isOpen: AtomicBoolean = new AtomicBoolean(true)
    ws.addWebSocketListener(new AddToQueueListener(queue, isOpen))
    new WebSocketImpl(ws, queue, isOpen, monad)
  }
}

class AddToQueueListener[F[_]](queue: SimpleQueue[F, WebSocketEvent], isOpen: AtomicBoolean)
    extends AHCWebSocketListener {
  override def onOpen(websocket: AHCWebSocket): Unit =
    throw new IllegalStateException("Should never be called!")

  override def onClose(websocket: AHCWebSocket, code: Int, reason: String): Unit =
    if (isOpen.getAndSet(false)) {
      queue.offer(WebSocketEvent.Frame(WebSocketFrame.Close(code, reason)))
    }

  override def onError(t: Throwable): Unit =
    if (isOpen.getAndSet(false)) {
      queue.offer(WebSocketEvent.Error(t))
    }

  override def onBinaryFrame(payload: Array[Byte], finalFragment: Boolean, rsv: Int): Unit =
    onFrame(WebSocketFrame.Binary(payload, finalFragment, rsvToOption(rsv)))
  override def onTextFrame(payload: String, finalFragment: Boolean, rsv: Int): Unit =
    onFrame(WebSocketFrame.Text(payload, finalFragment, rsvToOption(rsv)))

  override def onPingFrame(payload: Array[Byte]): Unit = onFrame(WebSocketFrame.Ping(payload))
  override def onPongFrame(payload: Array[Byte]): Unit = onFrame(WebSocketFrame.Pong(payload))

  private def onFrame(f: WebSocketFrame): Unit =
    try queue.offer(WebSocketEvent.Frame(f))
    catch {
      case e: Exception => onError(e)
    }

  private def rsvToOption(rsv: Int): Option[Int] = if (rsv == 0) None else Some(rsv)
}
