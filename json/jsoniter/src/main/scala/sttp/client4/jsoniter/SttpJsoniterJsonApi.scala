package sttp.client4.jsoniter

import sttp.client4.internal.Utf8
import sttp.client4.json.RichResponseAs
import sttp.client4.{
  asString,
  asStringAlways,
  BodySerializer,
  DeserializationException,
  HttpError,
  IsOption,
  JsonInput,
  ResponseAs,
  ResponseException,
  ShowError,
  StringBody
}
import sttp.model.MediaType

trait SttpJsoniterJsonApi {
  import com.github.plokhotnyuk.jsoniter_scala.core._
  import ShowError.showErrorMessageFromException
  implicit def jsoniterBodySerializer[B](implicit encoder: JsonValueCodec[B]): BodySerializer[B] =
    b => StringBody(writeToString(b), Utf8, MediaType.ApplicationJson)

  /** If the response is successful (2xx), tries to deserialize the body from a string into JSON. Returns:
    *   - `Right(b)` if the parsing was successful
    *   - `Left(HttpError(String))` if the response code was other than 2xx (deserialization is not attempted)
    *   - `Left(DeserializationException)` if there's an error during deserialization
    */
  def asJson[B: JsonValueCodec: IsOption]: ResponseAs[Either[ResponseException[String, Exception], B]] =
    asString.mapWithMetadata(ResponseAs.deserializeRightWithError(deserializeJson[B])).showAsJson

  /** Tries to deserialize the body from a string into JSON, regardless of the response code. Returns:
    *   - `Right(b)` if the parsing was successful
    *   - `Left(DeserializationException)` if there's an error during deserialization
    */
  def asJsonAlways[B: JsonValueCodec: IsOption]: ResponseAs[Either[DeserializationException[Exception], B]] =
    asStringAlways.map(ResponseAs.deserializeWithError(deserializeJson[B])).showAsJsonAlways

  /** Tries to deserialize the body from a string into JSON, using different deserializers depending on the status code.
    * Returns:
    *   - `Right(B)` if the response was 2xx and parsing was successful
    *   - `Left(HttpError(E))` if the response was other than 2xx and parsing was successful
    *   - `Left(DeserializationException)` if there's an error during deserialization
    */
  def asJsonEither[
      E: JsonValueCodec: IsOption,
      B: JsonValueCodec: IsOption
  ]: ResponseAs[Either[ResponseException[E, Exception], B]] =
    asJson[B].mapLeft {
      case de @ DeserializationException(_, _) => de
      case HttpError(e, code) => deserializeJson[E].apply(e).fold(DeserializationException(e, _), HttpError(_, code))
    }.showAsJsonEither

  def deserializeJson[B: JsonValueCodec: IsOption]: String => Either[Exception, B] = { (s: String) =>
    try Right(readFromString[B](JsonInput.sanitize[B].apply(s)))
    catch {
      case de: JsonReaderException => Left(DeserializationException[JsonReaderException](s, de))
    }
  }

  implicit def optionDecoder[T: JsonValueCodec]: JsonValueCodec[Option[T]] = new JsonValueCodec[Option[T]] {
    private val codec = implicitly[JsonValueCodec[T]]
    override def decodeValue(in: JsonReader, default: Option[T]): Option[T] =
      if (
        in.isNextToken('n'.toByte)
        && in.isNextToken('u'.toByte)
        && in.isNextToken('l'.toByte)
        && in.isNextToken('l'.toByte)
      ) {
        None
      } else {
        in.rollbackToken()
        Some(codec.decodeValue(in, codec.nullValue))
      }

    override def encodeValue(x: Option[T], out: JsonWriter): Unit = x.foreach(codec.encodeValue(_, out))

    override def nullValue: Option[T] = null
  }
}
