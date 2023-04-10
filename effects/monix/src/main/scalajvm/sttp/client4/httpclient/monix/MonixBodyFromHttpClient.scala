package sttp.client4.httpclient.monix

import monix.eval.Task
import monix.execution.Scheduler
import monix.nio.file._
import monix.reactive.{Consumer, Observable}
import sttp.capabilities.monix.MonixStreams
import sttp.client4.GenericWebSocketResponseAs
import sttp.client4.impl.monix.MonixWebSockets
import sttp.client4.internal.httpclient.BodyFromHttpClient
import sttp.client4.internal.{BodyFromResponseAs, SttpFile}
import sttp.client4.ws.{GotAWebSocketException, NotAWebSocketException}
import sttp.model.ResponseMetadata
import sttp.ws.{WebSocket, WebSocketFrame}

import java.nio.file.StandardOpenOption

private[monix] trait MonixBodyFromHttpClient extends BodyFromHttpClient[Task, MonixStreams, MonixStreams.BinaryStream] {
  override val streams: MonixStreams = MonixStreams
  implicit def scheduler: Scheduler

  override def compileWebSocketPipe(
      ws: WebSocket[Task],
      pipe: Observable[WebSocketFrame.Data[_]] => Observable[WebSocketFrame]
  ): Task[Unit] =
    MonixWebSockets.compilePipe(ws, pipe)

  override protected def bodyFromResponseAs
      : BodyFromResponseAs[Task, Observable[Array[Byte]], WebSocket[Task], Observable[Array[Byte]]] =
    new BodyFromResponseAs[Task, MonixStreams.BinaryStream, WebSocket[Task], MonixStreams.BinaryStream]() {
      override protected def withReplayableBody(
          response: Observable[Array[Byte]],
          replayableBody: Either[Array[Byte], SttpFile]
      ): Task[Observable[Array[Byte]]] =
        replayableBody match {
          case Left(value) => Task.pure(Observable.now(value))
          case Right(file) => Task.pure(readAsync(file.toPath, 32 * 1024))
        }

      override protected def regularIgnore(response: Observable[Array[Byte]]): Task[Unit] =
        response.consumeWith(Consumer.complete)

      override protected def regularAsByteArray(response: Observable[Array[Byte]]): Task[Array[Byte]] =
        response.consumeWith(Consumer.foldLeft(Array.emptyByteArray)(_ ++ _))

      override protected def regularAsFile(response: Observable[Array[Byte]], file: SttpFile): Task[SttpFile] =
        response
          .consumeWith(writeAsync(file.toPath, Seq(StandardOpenOption.WRITE, StandardOpenOption.CREATE)))
          .as(file)

      override protected def regularAsStream(
          response: Observable[Array[Byte]]
      ): Task[(Observable[Array[Byte]], () => Task[Unit])] =
        Task.pure((response, () => response.consumeWith(Consumer.complete).onErrorFallbackTo(Task.unit)))

      override protected def handleWS[T](
          responseAs: GenericWebSocketResponseAs[T, _],
          meta: ResponseMetadata,
          ws: WebSocket[Task]
      ): Task[T] = bodyFromWs(responseAs, ws, meta)

      override protected def cleanupWhenNotAWebSocket(
          response: Observable[Array[Byte]],
          e: NotAWebSocketException
      ): Task[Unit] = response.consumeWith(Consumer.complete)

      override protected def cleanupWhenGotWebSocket(response: WebSocket[Task], e: GotAWebSocketException): Task[Unit] =
        response.close()
    }
}
