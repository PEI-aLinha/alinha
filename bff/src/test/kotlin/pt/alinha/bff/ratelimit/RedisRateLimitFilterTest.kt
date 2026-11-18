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
import org.springframework.web.server.WebFilterChain
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class RedisRateLimitFilterTest {

    private val redis = mock(ReactiveStringRedisTemplate::class.java)
    private val chain = mock(WebFilterChain::class.java)
    private val props = BffProperties(rateLimit = BffProperties.RateLimit(requests = 2))
    private val filter = RedisRateLimitFilter(redis, props)

    private fun exchange(path: String = "/api/me") =
        MockServerWebExchange.builder(MockServerHttpRequest.get(path).build())
            .principal(UsernamePasswordAuthenticationToken("rui", null, emptyList()))
            .build()

    private fun redisAnswers(count: Long) {
        Mockito.`when`(
            redis.execute(
                Mockito.any<RedisScript<Long>>(),
                Mockito.anyList<String>(),
                Mockito.anyList<Any>(),
            ),
        ).thenReturn(Flux.just(count))
        Mockito.`when`(chain.filter(Mockito.any())).thenReturn(Mono.empty())
    }

    @Test
    fun `allows a request under the limit`() {
        redisAnswers(2)
        val exchange = exchange()

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        verify(chain).filter(exchange)
    }

    @Test
    fun `answers 429 with Retry-After over the limit and does not call the application`() {
        redisAnswers(3)
        val exchange = exchange()

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete()

        assertThat(exchange.response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(exchange.response.headers.getFirst("Retry-After")).isEqualTo("60")
        verify(chain, never()).filter(Mockito.any())
    }

    @Test
    fun `ignores paths outside the API`() {
        redisAnswers(3)
        val exchange = exchange("/actuator/health")

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
