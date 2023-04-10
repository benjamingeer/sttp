package sttp.client4.logging

import java.util.concurrent.TimeUnit
import sttp.client4.{GenericRequest, Response}
import sttp.client4.listener.RequestListener
import sttp.monad.MonadError
import sttp.monad.syntax._

import scala.concurrent.duration.Duration

class LoggingListener[F[_]](log: Log[F], includeTiming: Boolean)(implicit m: MonadError[F])
    extends RequestListener[F, Option[Long]] {
  private def now(): Long = System.currentTimeMillis()
  private def elapsed(from: Option[Long]): Option[Duration] = from.map(f => Duration(now() - f, TimeUnit.MILLISECONDS))

  override def beforeRequest(request: GenericRequest[_, _]): F[Option[Long]] =
    log.beforeRequestSend(request).map(_ => if (includeTiming) Some(now()) else None)

  override def requestException(request: GenericRequest[_, _], tag: Option[Long], e: Exception): F[Unit] =
    log.requestException(request, elapsed(tag), e)

  override def requestSuccessful(request: GenericRequest[_, _], response: Response[_], tag: Option[Long]): F[Unit] =
    log.response(request, response, None, elapsed(tag))
}
