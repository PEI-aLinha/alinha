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

    /** True when the request may proceed; fails open when Redis is unavailable. */
    fun allows(subject: String): Mono<Boolean> =
        redis.execute(
            INCREMENT_AND_EXPIRE,
            listOf("alinha:bff:rate:$subject"),
            listOf(props.rateLimit.window.toMillis().toString()),
        ).next()
            .map { it <= props.rateLimit.requests }
            .onErrorResume {
                // The session is in Redis too: when Redis is down the login is already unavailable. Do not add an outage.
                log.warn("Rate limiter unavailable, allowing the request: {}", it.message)
                Mono.just(true)
            }

    fun tooManyRequests(exchange: ServerWebExchange): Mono<Void> {
        val body = """{"type":"about:blank","title":"Too Many Requests","status":429}"""
        exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        exchange.response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        exchange.response.headers.set("Retry-After", props.rateLimit.window.seconds.toString())
        return exchange.response.writeWith(
            Mono.just(exchange.response.bufferFactory().wrap(body.toByteArray(StandardCharsets.UTF_8))),
        )
    }

    private companion object {
        val INCREMENT_AND_EXPIRE = DefaultRedisScript(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count
            """.trimIndent(),
            Long::class.java,
        )
    }
}
