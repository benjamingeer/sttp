package sttp.client4.asynchttpclient.future

import java.nio.ByteBuffer
import io.netty.buffer.ByteBuf
import org.asynchttpclient.{
  AsyncHttpClient,
  AsyncHttpClientConfig,
  BoundRequestBuilder,
  DefaultAsyncHttpClient,
  DefaultAsyncHttpClientConfig
}
import org.reactivestreams.Publisher
import sttp.client4.asynchttpclient.{AsyncHttpClientBackend, BodyFromAHC, BodyToAHC}
import sttp.client4.internal.NoStreams
import sttp.client4.internal.ws.SimpleQueue
import sttp.client4.testing.BackendStub
import sttp.client4.wrappers.FollowRedirectsBackend
import sttp.client4.{wrappers, Backend, BackendOptions}
import sttp.monad.{FutureMonad, MonadAsyncError}
import sttp.ws.WebSocket

import scala.concurrent.{ExecutionContext, Future}

class AsyncHttpClientFutureBackend private (
    asyncHttpClient: AsyncHttpClient,
    closeClient: Boolean,
    customizeRequest: BoundRequestBuilder => BoundRequestBuilder
)(implicit
    ec: ExecutionContext
) extends AsyncHttpClientBackend[Future, Nothing, Any](
      asyncHttpClient,
      new FutureMonad,
      closeClient,
      customizeRequest
    ) {

  override val streams: NoStreams = NoStreams

  override protected val bodyFromAHC: BodyFromAHC[Future, Nothing] = new BodyFromAHC[Future, Nothing] {
    override val streams: NoStreams = NoStreams
    override implicit val monad: MonadAsyncError[Future] = new FutureMonad
    override def publisherToStream(p: Publisher[ByteBuffer]): Nothing =
      throw new IllegalStateException("This backend does not support streaming")
    override def compileWebSocketPipe(ws: WebSocket[Future], pipe: Nothing): Future[Unit] =
      pipe // nothing is everything
  }

  override protected def bodyToAHC: BodyToAHC[Future, Nothing] =
    new BodyToAHC[Future, Nothing] {
      override val streams: NoStreams = NoStreams
      override protected def streamToPublisher(s: Nothing): Publisher[ByteBuf] = s // nothing is everything
    }

  override protected def createSimpleQueue[T]: Future[SimpleQueue[Future, T]] =
    throw new IllegalStateException("Web sockets are not supported!")
}

object AsyncHttpClientFutureBackend {
  private def apply(
      asyncHttpClient: AsyncHttpClient,
      closeClient: Boolean,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder
  )(implicit
      ec: ExecutionContext
  ): Backend[Future] =
    wrappers.FollowRedirectsBackend(new AsyncHttpClientFutureBackend(asyncHttpClient, closeClient, customizeRequest))

  /** @param ec
    *   The execution context for running non-network related operations, e.g. mapping responses. Defaults to the global
    *   execution context.
    */
  def apply(
      options: BackendOptions = BackendOptions.Default,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  )(implicit ec: ExecutionContext = ExecutionContext.global): Backend[Future] =
    AsyncHttpClientFutureBackend(AsyncHttpClientBackend.defaultClient(options), closeClient = true, customizeRequest)

  /** @param ec
    *   The execution context for running non-network related operations, e.g. mapping responses. Defaults to the global
    *   execution context.
    */
  def usingConfig(
      cfg: AsyncHttpClientConfig,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  )(implicit ec: ExecutionContext = ExecutionContext.global): Backend[Future] =
    AsyncHttpClientFutureBackend(new DefaultAsyncHttpClient(cfg), closeClient = true, customizeRequest)

  /** @param updateConfig
    *   A function which updates the default configuration (created basing on `options`).
    * @param ec
    *   The execution context for running non-network related operations, e.g. mapping responses. Defaults to the global
    *   execution context.
    */
  def usingConfigBuilder(
      updateConfig: DefaultAsyncHttpClientConfig.Builder => DefaultAsyncHttpClientConfig.Builder,
      options: BackendOptions = BackendOptions.Default,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  )(implicit ec: ExecutionContext = ExecutionContext.global): Backend[Future] =
    AsyncHttpClientFutureBackend(
      AsyncHttpClientBackend.clientWithModifiedOptions(options, updateConfig),
      closeClient = true,
      customizeRequest
    )

  /** @param ec
    *   The execution context for running non-network related operations, e.g. mapping responses. Defaults to the global
    *   execution context.
    */
  def usingClient(
      client: AsyncHttpClient,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  )(implicit ec: ExecutionContext = ExecutionContext.global): Backend[Future] =
    AsyncHttpClientFutureBackend(client, closeClient = false, customizeRequest)

  /** Create a stub backend for testing, which uses the [[Future]] response wrapper, and doesn't support streaming.
    *
    * See [[BackendStub]] for details on how to configure stub responses.
    */
  def stub(implicit ec: ExecutionContext = ExecutionContext.global): BackendStub[Future] =
    BackendStub(new FutureMonad())
}
