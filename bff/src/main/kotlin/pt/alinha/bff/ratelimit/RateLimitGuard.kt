package pt.alinha.bff.ratelimit

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import pt.alinha.bff.BffProperties
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

/** Fixed-window counter in Redis, shared by every BFF instance. */
@Component
class RateLimitGuard(
    private val redis: ReactiveStringRedisTemplate,
    private val props: BffProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Seconds the caller must wait after [limit] requests in the window, rounded up: 0 when the request may proceed. Fails open (0) when Redis is
     * unavailable.
     */
    fun retryAfterSeconds(subject: String, limit: Long): Mono<Long> =
        redis.execute(
            CHECK,
            listOf("alinha:bff:rate:$subject"),
            listOf(props.rateLimit.window.toMillis().toString(), limit.toString()),
        ).next()
            .map { waitMillis -> if (waitMillis <= 0) 0L else (waitMillis + 999) / 1000 }
            .onErrorResume {
                // The session is in Redis too: when Redis is down the login is already unavailable. Do not add an outage.
                log.warn("Rate limiter unavailable, allowing the request: {}", it.message)
                Mono.just(0L)
            }

    fun tooManyRequests(exchange: ServerWebExchange, retryAfterSeconds: Long): Mono<Void> {
        val body = """{"type":"about:blank","title":"Too Many Requests","status":429}"""
        exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        exchange.response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        exchange.response.headers.set("Retry-After", retryAfterSeconds.toString())
        return exchange.response.writeWith(
            Mono.just(exchange.response.bufferFactory().wrap(body.toByteArray(StandardCharsets.UTF_8))),
        )
    }

    private companion object {
        /** Counts the request; answers 0 under the limit, otherwise the milliseconds left in the window. */
        val CHECK = DefaultRedisScript(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if count <= tonumber(ARGV[2]) then return 0 end
            local left = redis.call('PTTL', KEYS[1])
            if left <= 0 then return 1 end
            return left
            """.trimIndent(),
            Long::class.java,
        )
    }
}
