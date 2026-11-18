package pt.alinha.bff.auth

import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext
import org.springframework.security.oauth2.client.RefreshTokenReactiveOAuth2AuthorizedClientProvider
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono

/** Forces a new access token after the account changes in Keycloak (registration, #102). */
@Service
class TokenRefreshService(
    private val clients: ServerOAuth2AuthorizedClientRepository,
    private val refreshProvider: RefreshTokenReactiveOAuth2AuthorizedClientProvider,
    private val props: BffProperties,
) {
    fun refresh(exchange: ServerWebExchange, authentication: Authentication): Mono<Void> =
        clients.loadAuthorizedClient<OAuth2AuthorizedClient>(props.registrationId, authentication, exchange)
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED)))
            .flatMap { client ->
                if (client.refreshToken == null) {
                    Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh token in the session"))
                } else {
                    val context = OAuth2AuthorizationContext.withAuthorizedClient(client)
                        .principal(authentication)
                        .build()
                    refreshProvider.authorize(context)
                        .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED)))
                        .flatMap { clients.saveAuthorizedClient(it, authentication, exchange) }
                }
            }
}
