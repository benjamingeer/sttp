package sttp.client4.impl.zio

import sttp.client4.internal.ws.SimpleQueue
import sttp.ws.WebSocketBufferFull
import zio.{Queue, RIO, Runtime}

class ZioSimpleQueue[R, A](queue: Queue[A], runtime: Runtime[Any]) extends SimpleQueue[RIO[R, *], A] {
  override def offer(t: A): Unit =
    if (!runtime.unsafeRun(queue.offer(t))) {
      throw WebSocketBufferFull(queue.capacity)
    }
  override def poll: RIO[R, A] =
    queue.take
}
