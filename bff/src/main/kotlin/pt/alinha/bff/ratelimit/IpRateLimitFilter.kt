package pt.alinha.bff.ratelimit

import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono

/**
 * Anonymous traffic (the login endpoints, and API calls before a session exists) must be limited by address.
 * This runs before Spring Security (order -100), which otherwise answers unauthenticated requests before
 * [RedisRateLimitFilter] gets a chance to count them. Requests with a session are left to that per-user filter.
 */
@Component
@Order(-150)
class IpRateLimitFilter(
    private val guard: RateLimitGuard,
    private val props: BffProperties,
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        val login = path.startsWith("/oauth2/") || path.startsWith("/login/")
        val anonymousApi =
            (path.startsWith("/api/") || path.startsWith("/auth/")) &&
                !exchange.request.cookies.containsKey(SESSION_COOKIE)
        if (!props.rateLimit.enabled || !(login || anonymousApi)) {
            return chain.filter(exchange)
        }
        val ip = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
        return guard.allows("ip:$ip")
            .flatMap { allowed -> if (allowed) chain.filter(exchange) else guard.tooManyRequests(exchange) }
    }

    private companion object {
        const val SESSION_COOKIE = "ALINHA_SESSION"
    }
}
