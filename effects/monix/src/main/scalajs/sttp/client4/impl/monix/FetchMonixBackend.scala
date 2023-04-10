package sttp.client4.impl.monix

import monix.eval.Task
import monix.reactive.Observable
import org.scalajs.dom.experimental.{BodyInit, Request => FetchRequest, Response => FetchResponse}
import sttp.capabilities.monix.MonixStreams
import sttp.client4.internal.ConvertFromFuture
import sttp.client4.testing.WebSocketStreamBackendStub
import sttp.client4.WebSocketStreamBackend
import sttp.client4.fetch.{AbstractFetchBackend, FetchOptions}
import sttp.ws.{WebSocket, WebSocketFrame}

import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.typedarray.{Int8Array, _}

/** Uses the `ReadableStream` interface from the Streams API.
  *
  * Streams are behind a flag on Firefox.
  *
  * Note that no browsers support a stream request body so it is converted into an in memory array first.
  *
  * @see
  *   https://developer.mozilla.org/en-US/docs/Web/API/ReadableStream
  */
class FetchMonixBackend private (fetchOptions: FetchOptions, customizeRequest: FetchRequest => FetchRequest)
    extends AbstractFetchBackend[Task, MonixStreams](fetchOptions, customizeRequest, TaskMonadAsyncError)
    with WebSocketStreamBackend[Task, MonixStreams] {

  override val streams: MonixStreams = MonixStreams

  override protected def addCancelTimeoutHook[T](result: Task[T], cancel: () => Unit): Task[T] = {
    val doCancel = Task.delay(cancel())
    result.doOnCancel(doCancel).doOnFinish(_ => doCancel)
  }

  override protected def handleStreamBody(s: Observable[Array[Byte]]): Task[js.UndefOr[BodyInit]] = {
    // as no browsers support a ReadableStream request body yet we need to create an in memory array
    // see: https://stackoverflow.com/a/41222366/4094860
    val bytes = s.foldLeftL(Array.emptyByteArray) { case (data, item) => data ++ item }
    bytes.map(_.toTypedArray.asInstanceOf[BodyInit])
  }

  override protected def handleResponseAsStream(
      response: FetchResponse
  ): Task[(Observable[Array[Byte]], () => Task[Unit])] =
    Task
      .delay {
        lazy val reader = response.body.getReader()

        def read() = convertFromFuture(reader.read().toFuture)

        def go(): Observable[Array[Byte]] =
          Observable.fromTask(read()).flatMap { chunk =>
            if (chunk.done) Observable.empty
            else {
              val bytes = new Int8Array(chunk.value.buffer).toArray
              Observable.pure(bytes) ++ go()
            }
          }
        val cancel = Task(reader.cancel("Response body reader cancelled")).void
        (go().doOnSubscriptionCancel(cancel), () => cancel)
      }

  override protected def compileWebSocketPipe(
      ws: WebSocket[Task],
      pipe: Observable[WebSocketFrame.Data[_]] => Observable[WebSocketFrame]
  ): Task[Unit] =
    MonixWebSockets.compilePipe(ws, pipe)

  override implicit def convertFromFuture: ConvertFromFuture[Task] = new ConvertFromFuture[Task] {
    override def apply[T](f: Future[T]): Task[T] = Task.fromFuture(f)
  }
}

object FetchMonixBackend {
  def apply(
      fetchOptions: FetchOptions = FetchOptions.Default,
      customizeRequest: FetchRequest => FetchRequest = identity
  ): WebSocketStreamBackend[Task, MonixStreams] =
    new FetchMonixBackend(fetchOptions, customizeRequest)

  /** Create a stub backend for testing, which uses the [[Task]] response wrapper, and supports `Observable[ByteBuffer]`
    * streaming.
    *
    * See [[WebSocketStreamBackendStub]] for details on how to configure stub responses.
    */
  def stub: WebSocketStreamBackendStub[Task, MonixStreams] = WebSocketStreamBackendStub(TaskMonadAsyncError)
}
