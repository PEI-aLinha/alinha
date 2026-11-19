package pt.alinha.bff.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder
import org.springframework.security.oauth2.client.RefreshTokenReactiveOAuth2AuthorizedClientProvider
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.server.WebFilterExchange
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.csrf.CsrfToken
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.UriComponentsBuilder
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono
import java.net.URI
import java.nio.charset.StandardCharsets

@Configuration
@EnableWebFluxSecurity
class SecurityConfig(private val props: BffProperties) {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .csrf {
                // The shell echoes the raw cookie value in X-XSRF-TOKEN, so skip the default Xor masking.
                it.csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                it.csrfTokenRequestHandler(ServerCsrfTokenRequestAttributeHandler())
            }
            .authorizeExchange {
                it.pathMatchers("/oauth2/**", "/login/**", "/error", "/actuator/health/**").permitAll()
                it.pathMatchers("/api/**", "/auth/**").authenticated()
                it.anyExchange().authenticated()
            }
            .exceptionHandling { it.authenticationEntryPoint { exchange, _ -> unauthorizedOrLogin(exchange) } }
            .oauth2Login {
                it.authenticationSuccessHandler(RedirectServerAuthenticationSuccessHandler(props.afterLoginUri))
            }
            .logout {
                // POST, not GET: a link must not be able to end someone's session (CSRF).
                it.requiresLogout(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/auth/logout"))
                it.logoutSuccessHandler(keycloakLogoutSuccessHandler())
            }
            .build()

    /** The token relay refreshes an expired access token with the stored refresh token. */
    @Bean
    fun authorizedClientManager(
        registrations: ReactiveClientRegistrationRepository,
        clients: ServerOAuth2AuthorizedClientRepository,
    ): ReactiveOAuth2AuthorizedClientManager {
        val provider = ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
            .authorizationCode()
            .refreshToken()
            .build()
        return DefaultReactiveOAuth2AuthorizedClientManager(registrations, clients).apply {
            setAuthorizedClientProvider(provider)
        }
    }

    @Bean
    fun refreshTokenProvider() = RefreshTokenReactiveOAuth2AuthorizedClientProvider()

    /**
     * The shell reads this cookie and sends it back as `X-XSRF-TOKEN` on writes. Getting the token from the
     * request attributes materialises the cookie on the first GET (`/api/me`).
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun csrfCookieWebFilter(): WebFilter = WebFilter { exchange, chain ->
        val token = exchange.getAttribute<Any>(CsrfToken::class.java.name)
        if (token is Mono<*>) token.then(chain.filter(exchange)) else chain.filter(exchange)
    }

    private fun unauthorizedOrLogin(exchange: ServerWebExchange): Mono<Void> {
        val path = exchange.request.path.value()
        return if (path.startsWith("/api/") || path.startsWith("/auth/")) {
            val body = """{"type":"about:blank","title":"Unauthorized","status":401}"""
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            exchange.response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
            exchange.response.writeWith(
                Mono.just(exchange.response.bufferFactory().wrap(body.toByteArray(StandardCharsets.UTF_8))),
            )
        } else {
            exchange.response.statusCode = HttpStatus.FOUND
            exchange.response.headers.location = URI.create("/oauth2/authorization/${props.registrationId}")
            exchange.response.setComplete()
        }
    }

    /** RP-initiated logout: clear the BFF session, then end the Keycloak SSO session too. */
    private fun keycloakLogoutSuccessHandler() = object : ServerLogoutSuccessHandler {
        override fun onLogoutSuccess(exchange: WebFilterExchange, authentication: Authentication): Mono<Void> {
            val idToken = (authentication.principal as? OidcUser)?.idToken?.tokenValue
            val builder = UriComponentsBuilder.fromUriString(props.endSessionUri)
                .queryParam("post_logout_redirect_uri", props.postLogoutUri)
            if (idToken != null) builder.queryParam("id_token_hint", idToken)
            val response = exchange.exchange.response
            response.statusCode = HttpStatus.FOUND
            response.headers.location = URI.create(builder.build().toUriString())
            return response.setComplete()
        }
    }
}
