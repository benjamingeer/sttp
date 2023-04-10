package sttp.client4.impl.cats

import cats.effect.Concurrent
import sttp.monad.{Canceler, MonadAsyncError}

class CatsMonadAsyncError[F[_]](implicit F: Concurrent[F]) extends CatsMonadError[F] with MonadAsyncError[F] {
  override def async[T](register: ((Either[Throwable, T]) => Unit) => Canceler): F[T] =
    F.cancelable(register.andThen(c => F.delay(c.cancel())))
}
