package sttp.client4.wrappers

import sttp.client4.GenericBackend
import sttp.monad.MonadError

/** A base class for delegate backends, which includes delegating implementations for `close` and `monad`, so that only
  * `send` needs to be defined.
  */
abstract class DelegateBackend[F[_], +P](delegate: GenericBackend[F, P]) extends GenericBackend[F, P] {
  override def close(): F[Unit] = delegate.close()
  override implicit def monad: MonadError[F] = delegate.monad
}
