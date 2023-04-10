package sttp.client4.impl.fs2

import cats.effect.{Effect, IO}
import fs2.concurrent.InspectableQueue
import sttp.client4.internal.ws.SimpleQueue
import sttp.ws.WebSocketBufferFull

class Fs2SimpleQueue[F[_], A](queue: InspectableQueue[F, A], capacity: Option[Int])(implicit F: Effect[F])
    extends SimpleQueue[F, A] {
  override def offer(t: A): Unit =
    F.toIO(queue.offer1(t))
      .flatMap {
        case true  => IO.unit
        case false => IO.raiseError(WebSocketBufferFull(capacity.getOrElse(Int.MaxValue)))
      }
      .unsafeRunSync()

  override def poll: F[A] = queue.dequeue1
}
