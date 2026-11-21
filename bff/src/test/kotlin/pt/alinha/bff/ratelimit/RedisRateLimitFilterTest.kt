package pt.alinha.bff.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.server.WebFilterChain
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class RedisRateLimitFilterTest {

    private val redis = mock(ReactiveStringRedisTemplate::class.java)
    private val chain = mock(WebFilterChain::class.java)
    private val props = BffProperties(rateLimit = BffProperties.RateLimit(requests = 2))
    private val guard = RateLimitGuard(redis, props)
    private val filter = RedisRateLimitFilter(guard, props)

    private fun exchange(path: String = "/api/me", principal: Any = UsernamePasswordAuthenticationToken("rui", null, emptyList())) =
        MockServerWebExchange.builder(MockServerHttpRequest.get(path).build())
            .principal(principal as org.springframework.security.core.Authentication)
            .build()

    /** What the script answers: 0 under the limit, otherwise the milliseconds left in the window. */
    private fun redisAnswers(waitMillis: Long) {
        Mockito.`when`(
            redis.execute(
                Mockito.any<RedisScript<Long>>(),
                Mockito.anyList<String>(),
                Mockito.anyList<Any>(),
            ),
        ).thenReturn(Flux.just(waitMillis))
        Mockito.`when`(chain.filter(Mockito.any())).thenReturn(Mono.empty())
    }

    @Test
    fun `allows a request under the limit`() {
        redisAnswers(0)
        val exchange = exchange()

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(chain).filter(exchange)
    }

    @Test
    fun `answers 429 with the time left in the window and does not call the application`() {
        redisAnswers(12_300)
        val exchange = exchange()

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(exchange.response.headers.getFirst("Retry-After")).isEqualTo("13")
        verify(chain, never()).filter(Mockito.any())
    }

    /** Rounding down would send 0 and the client would retry in a tight loop. */
    @Test
    fun `less than a second left is Retry-After 1`() {
        redisAnswers(300)
        val exchange = exchange()

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        assertThat(exchange.response.headers.getFirst("Retry-After")).isEqualTo("1")
    }

    @Test
    fun `ignores paths outside the API`() {
        redisAnswers(1_000)
        val exchange = exchange("/actuator/health")

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(chain).filter(exchange)
        verify(redis, never()).execute(Mockito.any<RedisScript<Long>>(), Mockito.anyList<String>(), Mockito.anyList<Any>())
    }

    @Test
    fun `keys an OIDC user by sub, not the mutable preferred_username`() {
        redisAnswers(0)
        val oidc = mock(OidcUser::class.java)
        Mockito.`when`(oidc.subject).thenReturn("stable-sub")
        val auth = UsernamePasswordAuthenticationToken(oidc, null, emptyList())
        val exchange = exchange(principal = auth)

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(redis).execute(
            Mockito.any<RedisScript<Long>>(),
            Mockito.eq(listOf("alinha:bff:rate:user:stable-sub")),
            Mockito.anyList<Any>(),
        )
    }

    @Test
    fun `a call without a principal is not counted here`() {
        redisAnswers(1_000)
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/me").build())

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(chain).filter(exchange)
        verify(redis, never()).execute(Mockito.any<RedisScript<Long>>(), Mockito.anyList<String>(), Mockito.anyList<Any>())
    }

    @Test
    fun `fails open when Redis is unavailable`() {
        Mockito.`when`(
            redis.execute(
                Mockito.any<RedisScript<Long>>(),
                Mockito.anyList<String>(),
                Mockito.anyList<Any>(),
            ),
        ).thenReturn(Flux.error(RuntimeException("Redis down")))
        Mockito.`when`(chain.filter(Mockito.any())).thenReturn(Mono.empty())
        val exchange = exchange()

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(chain).filter(exchange)
    }
}
