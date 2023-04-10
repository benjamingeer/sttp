package sttp.client4.asynchttpclient.cats

import java.io.{ByteArrayInputStream, File}
import java.nio.ByteBuffer
import cats.effect.implicits._
import cats.effect.{Concurrent, ContextShift, Resource, Sync}
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
import sttp.client4.impl.cats.CatsMonadAsyncError
import sttp.client4.internal.{FileHelpers, NoStreams}
import sttp.client4.{wrappers, Backend, BackendOptions, GenericRequest, Response}
import cats.implicits._
import sttp.client4.internal.ws.SimpleQueue
import sttp.client4.testing.BackendStub
import sttp.client4.wrappers.FollowRedirectsBackend
import sttp.monad.MonadAsyncError
import sttp.ws.WebSocket

class AsyncHttpClientCatsBackend[F[_]: Concurrent: ContextShift] private (
    asyncHttpClient: AsyncHttpClient,
    closeClient: Boolean,
    customizeRequest: BoundRequestBuilder => BoundRequestBuilder
) extends AsyncHttpClientBackend[F, Nothing, Any](
      asyncHttpClient,
      new CatsMonadAsyncError,
      closeClient,
      customizeRequest
    ) {

  override val streams: NoStreams = NoStreams

  override def send[T](r: GenericRequest[T, R]): F[Response[T]] =
    super.send(r).guarantee(implicitly[ContextShift[F]].shift)

  override protected val bodyFromAHC: BodyFromAHC[F, Nothing] = new BodyFromAHC[F, Nothing] {
    override val streams: NoStreams = NoStreams
    override implicit val monad: MonadAsyncError[F] = new CatsMonadAsyncError
    override def publisherToStream(p: Publisher[ByteBuffer]): Nothing =
      throw new IllegalStateException("This backend does not support streaming")
    override def compileWebSocketPipe(ws: WebSocket[F], pipe: Nothing): F[Unit] = pipe // nothing is everything

    override def publisherToFile(p: Publisher[ByteBuffer], f: File): F[Unit] =
      publisherToBytes(p)
        .guarantee(implicitly[ContextShift[F]].shift)
        .map(bytes => FileHelpers.saveFile(f, new ByteArrayInputStream(bytes)))
  }

  override protected def bodyToAHC: BodyToAHC[F, Nothing] =
    new BodyToAHC[F, Nothing] {
      override val streams: NoStreams = NoStreams
      override protected def streamToPublisher(s: Nothing): Publisher[ByteBuf] = s // nothing is everything
    }

  override protected def createSimpleQueue[T]: F[SimpleQueue[F, T]] =
    throw new IllegalStateException("Web sockets are not supported!")
}

object AsyncHttpClientCatsBackend {
  private def apply[F[_]: Concurrent: ContextShift](
      asyncHttpClient: AsyncHttpClient,
      closeClient: Boolean,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder
  ): Backend[F] =
    wrappers.FollowRedirectsBackend(new AsyncHttpClientCatsBackend(asyncHttpClient, closeClient, customizeRequest))

  /** After sending a request, always shifts to the thread pool backing the given `ContextShift[F]`.
    */
  def apply[F[_]: Concurrent: ContextShift](
      options: BackendOptions = BackendOptions.Default,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  ): F[Backend[F]] =
    Sync[F].delay(
      AsyncHttpClientCatsBackend(AsyncHttpClientBackend.defaultClient(options), closeClient = true, customizeRequest)
    )

  /** Makes sure the backend is closed after usage. After sending a request, always shifts to the thread pool backing
    * the given `ContextShift[F]`.
    */
  def resource[F[_]: Concurrent: ContextShift](
      options: BackendOptions = BackendOptions.Default,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  ): Resource[F, Backend[F]] =
    Resource.make(apply(options, customizeRequest))(_.close())

  /** After sending a request, always shifts to the thread pool backing the given `ContextShift[F]`.
    */
  def usingConfig[F[_]: Concurrent: ContextShift](
      cfg: AsyncHttpClientConfig,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  ): F[Backend[F]] =
    Sync[F].delay(AsyncHttpClientCatsBackend(new DefaultAsyncHttpClient(cfg), closeClient = true, customizeRequest))

  /** Makes sure the backend is closed after usage. After sending a request, always shifts to the thread pool backing
    * the given `ContextShift[F]`.
    */
  def resourceUsingConfig[F[_]: Concurrent: ContextShift](
      cfg: AsyncHttpClientConfig,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  ): Resource[F, Backend[F]] =
    Resource.make(usingConfig(cfg, customizeRequest))(_.close())

  /** After sending a request, always shifts to the thread pool backing the given `ContextShift[F]`.
    * @param updateConfig
    *   A function which updates the default configuration (created basing on `options`).
    */
  def usingConfigBuilder[F[_]: Concurrent: ContextShift](
      updateConfig: DefaultAsyncHttpClientConfig.Builder => DefaultAsyncHttpClientConfig.Builder,
      options: BackendOptions = BackendOptions.Default,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  ): F[Backend[F]] =
    Sync[F].delay(
      AsyncHttpClientCatsBackend(
        AsyncHttpClientBackend.clientWithModifiedOptions(options, updateConfig),
        closeClient = true,
        customizeRequest
      )
    )

  /** Makes sure the backend is closed after usage. After sending a request, always shifts to the thread pool backing
    * the given `ContextShift[F]`.
    * @param updateConfig
    *   A function which updates the default configuration (created basing on `options`).
    */
  def resourceUsingConfigBuilder[F[_]: Concurrent: ContextShift](
      updateConfig: DefaultAsyncHttpClientConfig.Builder => DefaultAsyncHttpClientConfig.Builder,
      options: BackendOptions = BackendOptions.Default,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  ): Resource[F, Backend[F]] =
    Resource.make(usingConfigBuilder(updateConfig, options, customizeRequest))(_.close())

  /** After sending a request, always shifts to the thread pool backing the given `ContextShift[F]`.
    */
  def usingClient[F[_]: Concurrent: ContextShift](
      client: AsyncHttpClient,
      customizeRequest: BoundRequestBuilder => BoundRequestBuilder = identity
  ): Backend[F] =
    AsyncHttpClientCatsBackend(client, closeClient = false, customizeRequest)

  /** Create a stub backend for testing, which uses the `F` response wrapper, and doesn't support streaming.
    *
    * See [[SttpBackendStub]] for details on how to configure stub responses.
    */
  def stub[F[_]: Concurrent]: BackendStub[F] = BackendStub(new CatsMonadAsyncError())
}
