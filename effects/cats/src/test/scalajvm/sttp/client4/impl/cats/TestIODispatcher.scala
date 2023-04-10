package sttp.client4.impl.cats

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import org.scalatest.{BeforeAndAfterAll, Suite}

trait TestIODispatcher extends BeforeAndAfterAll { this: Suite =>

  // use a var to avoid initialization error `scala.UninitializedFieldError`
  protected var dispatcher: Dispatcher[IO] = _

  private val (d, shutdownDispatcher) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
  dispatcher = d

  override protected def afterAll(): Unit = {
    shutdownDispatcher.unsafeRunSync()
    super.afterAll()
  }
}
