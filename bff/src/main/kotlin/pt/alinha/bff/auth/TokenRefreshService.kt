package pt.alinha.bff.auth

import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono

/**
 * Forces a new access token after the account changes in Keycloak (registration, #102). Calls the refresh token
 * grant directly: `RefreshTokenReactiveOAuth2AuthorizedClientProvider` skips tokens that have not expired yet.
 * The new id_token is dropped, so name and email in `/api/me` keep their login-time values until the next login.
 */
@Service
class TokenRefreshService(
    private val clients: ServerOAuth2AuthorizedClientRepository,
    private val tokenClient: ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest>,
    private val props: BffProperties,
) {
    fun refresh(exchange: ServerWebExchange, authentication: Authentication): Mono<Void> =
        clients.loadAuthorizedClient<OAuth2AuthorizedClient>(props.registrationId, authentication, exchange)
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED)))
            .flatMap { client ->
                val refreshToken = client.refreshToken
                    ?: return@flatMap Mono.error(
                        ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh token in the session"),
                    )
                tokenClient.getTokenResponse(
                    OAuth2RefreshTokenGrantRequest(client.clientRegistration, client.accessToken, refreshToken),
                )
                    // invalid_grant: the refresh token or Keycloak SSO session expired, so the user must log in again.
                    .onErrorMap(OAuth2AuthorizationException::class.java) {
                        ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token rejected", it)
                    }
                    .flatMap { response ->
                        val refreshed = OAuth2AuthorizedClient(
                            client.clientRegistration,
                            client.principalName,
                            response.accessToken,
                            // Keycloak may not rotate the refresh token: keep the current one then.
                            response.refreshToken ?: refreshToken,
                        )
                        clients.saveAuthorizedClient(refreshed, authentication, exchange)
                    }
            }
}
