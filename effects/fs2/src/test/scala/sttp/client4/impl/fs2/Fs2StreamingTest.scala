package sttp.client4.impl.fs2

import cats.effect.IO
import cats.instances.string._
import fs2.{Chunk, Stream}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.impl.cats.CatsTestBase
import sttp.model.sse.ServerSentEvent
import sttp.client4.testing.streaming.StreamingTest

trait Fs2StreamingTest extends StreamingTest[IO, Fs2Streams[IO]] with CatsTestBase {
  override val streams: Fs2Streams[IO] = new Fs2Streams[IO] {}

  override def bodyProducer(chunks: Iterable[Array[Byte]]): Stream[IO, Byte] =
    Stream
      .fromIterator[IO](chunks.iterator, chunks.size)
      .map(Chunk.array(_))
      .flatMap(Stream.chunk)

  override def bodyConsumer(stream: fs2.Stream[IO, Byte]): IO[String] =
    stream
      .through(fs2.text.utf8Decode)
      .compile
      .foldMonoid

  def sseConsumer(stream: streams.BinaryStream): IO[List[ServerSentEvent]] =
    stream.through(Fs2ServerSentEvents.parse).compile.toList
}
