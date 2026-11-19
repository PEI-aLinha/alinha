package pt.alinha.bff.ratelimit

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono

/**
 * Limits the login endpoints and every API call by address, before Spring Security can answer an
 * unauthenticated request with 401 (which would stop [RedisRateLimitFilter] from counting it).
 * A session cookie is not proof of authentication — it can be forged — so it is not trusted here.
 * Authenticated API calls are additionally limited per user by [RedisRateLimitFilter].
 */
@Component
@Order(-150)
class IpRateLimitFilter(
    private val guard: RateLimitGuard,
    private val props: BffProperties,
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        val limited =
            path.startsWith("/oauth2/") ||
                path.startsWith("/login/") ||
                path.startsWith("/api/") ||
                path.startsWith("/auth/")
        if (!props.rateLimit.enabled || !limited) {
            return chain.filter(exchange)
        }
        val ip = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
        return guard.retryAfterSeconds("ip:$ip")
            .flatMap { wait -> if (wait == 0L) chain.filter(exchange) else guard.tooManyRequests(exchange, wait) }
    }
}
