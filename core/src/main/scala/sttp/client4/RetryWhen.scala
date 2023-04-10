package sttp.client4

import sttp.model.Method

object RetryWhen {
  def isBodyRetryable(body: GenericRequestBody[_]): Boolean =
    body match {
      case NoBody              => true
      case _: StringBody       => true
      case _: ByteArrayBody    => true
      case _: ByteBufferBody   => true
      case _: InputStreamBody  => false
      case _: FileBody         => true
      case StreamBody(_)       => false
      case m: MultipartBody[_] => m.parts.forall(p => isBodyRetryable(p.body))
    }

  val Default: RetryWhen = {
    case (_, Left(_: SttpClientException.ConnectException)) => true
    case (_, Left(_))                                       => false
    case (request, Right(response)) =>
      isBodyRetryable(request.body) && Method.isIdempotent(request.method) && response.code.isServerError
  }
}
