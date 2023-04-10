package sttp.client4.okhttp

import org.scalatest.Assertion
import sttp.client4._
import sttp.monad.syntax._
import sttp.client4.monad.IdMonad
import sttp.client4.testing.ConvertToFuture
import sttp.client4.testing.HttpTest.wsEndpoint
import sttp.client4.testing.websocket.WebSocketTest
import sttp.monad.MonadError

import scala.concurrent.duration._

class OkHttpSyncWebSocketTest extends WebSocketTest[Identity] {
  override val backend: WebSocketBackend[Identity] = OkHttpSyncBackend()
  override implicit val convertToFuture: ConvertToFuture[Identity] = ConvertToFuture.id
  override implicit val monad: MonadError[Identity] = IdMonad

  override def throwsWhenNotAWebSocket: Boolean = true

  it should "error if incoming messages overflow the buffer" in {
    basicRequest
      .get(uri"$wsEndpoint/ws/echo")
      .response(asWebSocket[Identity, Assertion] { ws =>
        sendText(ws, OkHttpBackend.DefaultWebSocketBufferCapacity.get + 1).flatMap(_ =>
          eventually(10.millis, 400)(() => ws.isOpen().map(_ shouldBe false))
        )
      })
      .send(backend)
      .body match {
      case Left(value) => throw new RuntimeException(value)
      case Right(_)    => succeed
    }
  }

  private def eventually[T](interval: FiniteDuration, attempts: Int)(f: () => T): T = {
    Thread.sleep(interval.toMillis)
    try f()
    catch {
      case e if attempts > 0 => eventually(interval, attempts - 1)(f)
    }
  }
}
