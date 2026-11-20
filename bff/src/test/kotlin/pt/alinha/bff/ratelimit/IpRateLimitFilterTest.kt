package pt.alinha.bff.ratelimit

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.net.InetSocketAddress

class IpRateLimitFilterTest {

    private val guard = mock(RateLimitGuard::class.java)
    private val chain = mock(WebFilterChain::class.java)
    private val filter = IpRateLimitFilter(guard, BffProperties())

    private fun exchange(path: String) =
        MockServerWebExchange.from(
            MockServerHttpRequest.get(path).remoteAddress(InetSocketAddress("10.0.0.7", 1234)).build(),
        )

    @Test
    fun `limits the anonymous login endpoints by address`() {
        val exchange = exchange("/login/oauth2/code/keycloak")
        Mockito.`when`(guard.retryAfterSeconds("ip:10.0.0.7", 120)).thenReturn(Mono.just(0L))
        Mockito.`when`(chain.filter(Mockito.any())).thenReturn(Mono.empty())

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(chain).filter(exchange)
    }

    @Test
    fun `answers 429 when the address is over the limit`() {
        val exchange = exchange("/oauth2/authorization/keycloak")
        Mockito.`when`(guard.retryAfterSeconds("ip:10.0.0.7", 120)).thenReturn(Mono.just(42L))
        Mockito.`when`(guard.tooManyRequests(exchange, 42L)).thenReturn(Mono.empty())

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(guard).tooManyRequests(exchange, 42L)
        verify(chain, never()).filter(Mockito.any())
    }

    @Test
    fun `limits an API call by address in its own, higher bucket`() {
        val exchange = exchange("/api/me")
        Mockito.`when`(guard.retryAfterSeconds("ip-api:10.0.0.7", 1200)).thenReturn(Mono.just(0L))
        Mockito.`when`(chain.filter(Mockito.any())).thenReturn(Mono.empty())

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(chain).filter(exchange)
    }

    @Test
    fun `counts an API call with a forged session cookie by address`() {
        // A cookie is not proof of authentication: Security answers 401 before the per-user filter runs,
        // so this call must still be counted here.
        val exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/me")
                .cookie(org.springframework.http.HttpCookie("ALINHA_SESSION", "x"))
                .remoteAddress(InetSocketAddress("10.0.0.7", 1234))
                .build(),
        )
        Mockito.`when`(guard.retryAfterSeconds("ip-api:10.0.0.7", 1200)).thenReturn(Mono.just(0L))
        Mockito.`when`(chain.filter(Mockito.any())).thenReturn(Mono.empty())

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(guard).retryAfterSeconds("ip-api:10.0.0.7", 1200)
        verify(chain).filter(exchange)
    }
}
