package sttp.client4.impl.fs2

import fs2.text
import sttp.model.sse.ServerSentEvent

object Fs2ServerSentEvents {
  def parse[F[_]]: fs2.Pipe[F, Byte, ServerSentEvent] = { response =>
    response
      .through(text.utf8Decode[F])
      .through(text.lines[F])
      .split(_.isEmpty)
      .filter(_.nonEmpty)
      .map(_.toList)
      .map(ServerSentEvent.parse)
  }
}
