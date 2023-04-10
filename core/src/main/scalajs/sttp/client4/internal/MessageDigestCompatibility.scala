package sttp.client4.internal

import scala.scalajs.js.JSConverters._

private[client4] class MessageDigestCompatibility(algorithm: String) {
  private lazy val md: scala.scalajs.js.typedarray.ArrayBuffer => String = algorithm match {
    case "MD5" => SparkMD5.ArrayBuffer.hash(_)
    case _     => throw new IllegalArgumentException(s"Unsupported algorithm: $algorithm")
  }

  def digest(input: Array[Byte]): Array[Byte] =
    md(input.toJSArray.asInstanceOf[scala.scalajs.js.typedarray.ArrayBuffer]).getBytes("UTF-8")
}
