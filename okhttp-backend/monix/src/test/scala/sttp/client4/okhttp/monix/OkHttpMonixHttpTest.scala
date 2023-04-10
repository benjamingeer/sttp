package sttp.client4.okhttp.monix

import java.util.concurrent.TimeoutException

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import sttp.capabilities.monix.MonixStreams
import sttp.client4.StreamBackend
import sttp.client4.impl.monix.convertMonixTaskToFuture
import sttp.client4.testing.{ConvertToFuture, HttpTest}

import scala.concurrent.duration.DurationInt

class OkHttpMonixHttpTest extends HttpTest[Task] {

  override val backend: StreamBackend[Task, MonixStreams] = OkHttpMonixBackend().runSyncUnsafe()
  override implicit val convertToFuture: ConvertToFuture[Task] = convertMonixTaskToFuture

  override def supportsDeflateWrapperChecking = false

  override def timeoutToNone[T](t: Task[T], timeoutMillis: Int): Task[Option[T]] =
    t.map(Some(_))
      .timeout(timeoutMillis.milliseconds)
      .onErrorRecover { case _: TimeoutException =>
        None
      }
}
