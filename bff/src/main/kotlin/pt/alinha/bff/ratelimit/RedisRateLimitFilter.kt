package pt.alinha.bff.ratelimit

import org.springframework.core.annotation.Order
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono

/**
 * Per-user limiter for the authenticated API. Runs after Spring Security so the key can use the token subject.
 * Login and OAuth endpoints are covered earlier by [IpRateLimitFilter].
 */
@Component
@Order(-50)
class RedisRateLimitFilter(
    private val guard: RateLimitGuard,
    private val props: BffProperties,
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        if (!props.rateLimit.enabled || !(path.startsWith("/api/") || path.startsWith("/auth/"))) {
            return chain.filter(exchange)
        }
        return exchange.getPrincipal<Authentication>()
            // `sub` is stable; the OIDC name is preferred_username, which a person can change.
            .map { "user:${(it.principal as? OidcUser)?.subject ?: it.name}" }
            .flatMap { guard.retryAfterSeconds(it, props.rateLimit.requests) }
            // Security (order -100) already answered 401 to anonymous calls, so there is nothing to count here.
            .defaultIfEmpty(0L)
            .flatMap { wait -> if (wait == 0L) chain.filter(exchange) else guard.tooManyRequests(exchange, wait) }
    }
}
