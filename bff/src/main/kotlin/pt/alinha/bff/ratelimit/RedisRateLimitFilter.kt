package pt.alinha.bff.ratelimit

import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

/**
 * Fixed-window limiter in Redis, shared by every BFF instance. Runs after Spring Security so the key can use
 * the authenticated user; unauthenticated traffic is keyed by address.
 */
@Component
@Order(-50)
class RedisRateLimitFilter(
    private val redis: ReactiveStringRedisTemplate,
    private val props: BffProperties,
) : WebFilter {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        if (!props.rateLimit.enabled || !(path.startsWith("/api/") || path.startsWith("/auth/"))) {
            return chain.filter(exchange)
        }
        return exchange.getPrincipal<Authentication>()
            .map { "user:${it.name}" }
            .defaultIfEmpty("ip:${exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"}")
            .flatMap { subject -> increment(subject, props.rateLimit.window.toMillis()) }
            .flatMap { count ->
                if (count > props.rateLimit.requests) tooManyRequests(exchange) else chain.filter(exchange)
            }
    }

    private fun increment(subject: String, windowMillis: Long): Mono<Long> =
        redis.execute(
            INCREMENT_AND_EXPIRE,
            listOf("alinha:bff:rate:$subject"),
            listOf(windowMillis.toString()),
        ).next().onErrorResume {
            // The session is in Redis too: when Redis is down the login is already unavailable. Do not add an outage.
            log.warn("Rate limiter unavailable, allowing the request: {}", it.message)
            Mono.just(0L)
        }

    private fun tooManyRequests(exchange: ServerWebExchange): Mono<Void> {
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
