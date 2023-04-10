package sttp.client4

import java.io.IOException
import java.net.{InetSocketAddress, SocketAddress}
import java.{net, util}

import sttp.client4.BackendOptions._

import scala.concurrent.duration._
import scala.util.Try

case class BackendOptions(
    connectionTimeout: FiniteDuration,
    proxy: Option[Proxy]
) {

  /** When the request is sent, if the connection can't be established during the given period of time, a failed effect
    * will be returned, or an exception will be thrown
    */
  def connectionTimeout(ct: FiniteDuration): BackendOptions =
    this.copy(connectionTimeout = ct)
  def httpProxy(host: String, port: Int): BackendOptions =
    this.copy(proxy = Some(Proxy(host, port, ProxyType.Http)))
  def httpProxy(host: String, port: Int, username: String, password: String): BackendOptions =
    this.copy(proxy = Some(Proxy(host, port, ProxyType.Http, auth = Some(ProxyAuth(username, password)))))
  def socksProxy(host: String, port: Int): BackendOptions =
    this.copy(proxy = Some(Proxy(host, port, ProxyType.Socks)))
  def socksProxy(host: String, port: Int, username: String, password: String): BackendOptions =
    this.copy(proxy = Some(Proxy(host, port, ProxyType.Socks, auth = Some(ProxyAuth(username, password)))))
}

object BackendOptions {
  case class ProxyAuth(username: String, password: String)
  case class Proxy(
      host: String,
      port: Int,
      proxyType: ProxyType,
      nonProxyHosts: List[String] = Nil,
      auth: Option[ProxyAuth] = None,
      onlyProxyHosts: List[String] = Nil
  ) {
    // only matches prefix or suffix wild card(*)
    private def isWildCardMatch(targetHost: String, nonProxyHost: String): Boolean =
      if (nonProxyHost.length > 1) {
        if (nonProxyHost.charAt(0) == '*') {
          targetHost.regionMatches(
            true,
            targetHost.length - nonProxyHost.length + 1,
            nonProxyHost,
            1,
            nonProxyHost.length - 1
          )
        } else if (nonProxyHost.charAt(nonProxyHost.length - 1) == '*') {
          targetHost.regionMatches(true, 0, nonProxyHost, 0, nonProxyHost.length - 1)
        } else {
          nonProxyHost.equalsIgnoreCase(targetHost)
        }
      } else {
        nonProxyHost.equalsIgnoreCase(targetHost)
      }

    def ignoreProxy(host: String): Boolean =
      matchesNonProxyHost(host) || doesNotMatchAnyHostToProxy(host)

    private def matchesNonProxyHost(host: String) =
      nonProxyHosts.exists(isWildCardMatch(host, _))

    private def doesNotMatchAnyHostToProxy(host: String) =
      onlyProxyHosts != Nil && !onlyProxyHosts.exists(isWildCardMatch(host, _))

    def asJavaProxySelector: net.ProxySelector =
      new net.ProxySelector {
        override def select(uri: net.URI): util.List[net.Proxy] = {
          val proxyList = new util.ArrayList[net.Proxy](1)
          val uriHost = uri.getHost
          if (ignoreProxy(uriHost)) {
            proxyList.add(net.Proxy.NO_PROXY)
          } else {
            proxyList.add(asJavaProxy)
          }
          proxyList
        }

        override def connectFailed(uri: net.URI, sa: SocketAddress, ioe: IOException): Unit =
          throw new UnsupportedOperationException(
            s"Couldn't connect to the proxy server, uri: $uri, socket: $sa",
            ioe
          )
      }
    def asJavaProxy = new java.net.Proxy(proxyType.asJava, inetSocketAddress)
    def inetSocketAddress: InetSocketAddress =
      InetSocketAddress.createUnresolved(host, port)

    def authenticated(username: String, password: String): Proxy = this.copy(auth = Some(ProxyAuth(username, password)))
  }

  sealed trait ProxyType {
    def asJava: java.net.Proxy.Type
  }

  object ProxyType {
    case object Http extends ProxyType {
      override def asJava: java.net.Proxy.Type = java.net.Proxy.Type.HTTP
    }
    case object Socks extends ProxyType {
      override def asJava: java.net.Proxy.Type = java.net.Proxy.Type.SOCKS
    }
  }

  private val Empty: BackendOptions =
    BackendOptions(30.seconds, None)

  val Default: BackendOptions =
    Empty.copy(proxy = loadSystemProxy)

  def connectionTimeout(ct: FiniteDuration): BackendOptions =
    Default.connectionTimeout(ct)

  def httpProxy(host: String, port: Int): BackendOptions =
    Empty.httpProxy(host, port)

  def httpProxy(host: String, port: Int, username: String, password: String): BackendOptions =
    Empty.httpProxy(host, port, username, password)

  def socksProxy(host: String, port: Int): BackendOptions =
    Empty.socksProxy(host, port)

  def socksProxy(host: String, port: Int, username: String, password: String): BackendOptions =
    Empty.socksProxy(host, port, username, password)

  private def loadSystemProxy: Option[Proxy] = {
    def system(
        hostProp: String,
        portProp: String,
        nonProxyHostsPropOption: Option[String],
        make: (String, Int, List[String]) => Proxy,
        defaultPort: Int
    ) = {
      val host = Option(System.getProperty(hostProp))
      def port = Try(System.getProperty(portProp).toInt).getOrElse(defaultPort)
      def nonProxyHosts: List[String] =
        nonProxyHostsPropOption
          .map(nonProxyHostsProp =>
            Try(Option(System.getProperty(nonProxyHostsProp))).toOption.flatten.getOrElse("localhost|127.*")
          )
          .map(_.split('|').toList)
          .getOrElse(Nil)
      host.map(make(_, port, nonProxyHosts))
    }

    def proxy(t: ProxyType)(host: String, port: Int, nonProxyHosts: List[String]) = Proxy(host, port, t, nonProxyHosts)

    def addAuth(proxy: Option[Proxy], usernameProp: String, passwordProp: String): Option[Proxy] =
      for {
        plainProxy <- proxy
        username <- Option(System.getProperty(usernameProp))
        password <- Option(System.getProperty(passwordProp))
      } yield plainProxy.authenticated(username, password)

    import ProxyType._

    val socks = system("socksProxyHost", "socksProxyPort", None, proxy(Socks), 1080)
    // socks has system properties for specifying authentication
    val socksWithAuth = addAuth(socks, "java.net.socks.username", "java.net.socks.password")

    val http = system("http.proxyHost", "http.proxyPort", Some("http.nonProxyHosts"), proxy(Http), 80)

    val httpWithAuth = addAuth(http, "http.proxyUsername", "http.proxyPassword")

    // https uses the nonProxyHosts of http
    val https = system("https.proxyHost", "https.proxyPort", Some("http.nonProxyHosts"), proxy(Http), 443)

    val httpsWithAuth = addAuth(https, "https.proxyUsername", "https.proxyPassword")

    Seq(socksWithAuth, socks, httpWithAuth, http, httpsWithAuth, https).find(_.isDefined).flatten
  }
}
