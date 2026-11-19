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
        Mockito.`when`(guard.allows("ip:10.0.0.7")).thenReturn(Mono.just(true))
        Mockito.`when`(chain.filter(Mockito.any())).thenReturn(Mono.empty())

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(chain).filter(exchange)
    }

    @Test
    fun `answers 429 when the address is over the limit`() {
        val exchange = exchange("/oauth2/authorization/keycloak")
        Mockito.`when`(guard.allows("ip:10.0.0.7")).thenReturn(Mono.just(false))
        Mockito.`when`(guard.tooManyRequests(exchange)).thenReturn(Mono.empty())

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(guard).tooManyRequests(exchange)
        verify(chain, never()).filter(Mockito.any())
    }

    @Test
    fun `leaves the authenticated API to the per-user filter`() {
        val exchange = exchange("/api/me")
        Mockito.`when`(chain.filter(Mockito.any())).thenReturn(Mono.empty())

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(chain).filter(exchange)
        verify(guard, never()).allows(Mockito.anyString())
    }
}
