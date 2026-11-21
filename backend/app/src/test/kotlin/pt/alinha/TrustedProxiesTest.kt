package pt.alinha

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.env.Environment
import fixture.remoteip.RemoteAddrFixtureApplication
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private const val CLIENT = "203.0.113.7"

/** Asks the fixture, over loopback, which client address the app sees when the caller claims [forwardedFor]. */
private fun remoteAddr(port: Int, forwardedFor: String): String {
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/fixture/remote-addr"))
        .header("X-Forwarded-For", forwardedFor)
        .build()
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body()
}

/**
 * The registration rate limit keys on the client IP (ADR 0004). `X-Forwarded-For` may replace the socket address
 * only when the request comes from a trusted proxy (the BFF), never from any private address, or anything on the
 * container network could pick its own key.
 */
@SpringBootTest(classes = [RemoteAddrFixtureApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrustedProxiesTest {
    @LocalServerPort private var port = 0
    @Autowired private lateinit var env: Environment

    @Test
    fun `only loopback is a trusted proxy by default`() {
        val trusted = Regex(env.getRequiredProperty("server.tomcat.remoteip.internal-proxies"))

        listOf("127.0.0.1", "0:0:0:0:0:0:0:1").forEach { assertThat(trusted.matches(it)).describedAs(it).isTrue() }
        listOf("10.0.0.1", "172.17.0.2", "192.168.1.10", "169.254.1.1", "100.64.0.1", "203.0.113.7")
            .forEach { assertThat(trusted.matches(it)).describedAs(it).isFalse() }
    }

    @Test
    fun `a request from a trusted proxy is seen as the forwarded client`() {
        assertThat(remoteAddr(port, CLIENT)).isEqualTo(CLIENT)
    }
}

/** With the BFF named elsewhere (production), a loopback caller can no longer choose its address. */
@SpringBootTest(
    classes = [RemoteAddrFixtureApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["ALINHA_TRUSTED_PROXIES=10\\.9\\.9\\.9"],
)
class UntrustedProxyTest {
    @LocalServerPort private var port = 0

    @Test
    fun `X-Forwarded-For from an untrusted address is ignored`() {
        assertThat(remoteAddr(port, CLIENT)).isEqualTo("127.0.0.1")
    }
}
