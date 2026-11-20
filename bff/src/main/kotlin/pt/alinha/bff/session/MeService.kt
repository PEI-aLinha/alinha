package pt.alinha.bff.session

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

private const val ORGANIZATION_CONCURRENCY = 4
private val NO_ROLES = InternalMe(emptyList(), emptyList(), emptyList())

@Service
class MeService(
    private val app: AppClient,
    private val clients: ReactiveOAuth2AuthorizedClientManager,
    private val props: BffProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun me(exchange: ServerWebExchange, authentication: Authentication, user: OidcUser): Mono<MeResponse> =
        clients.authorize(
            OAuth2AuthorizeRequest.withClientRegistrationId(props.registrationId)
                .principal(authentication)
                .attribute(ServerWebExchange::class.java.name, exchange)
                .build(),
        )
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED)))
            .flatMap { client ->
                val token = client.accessToken.tokenValue
                app.internalMe(token)
                    // The app is deny-by-default: a valid token with no known role lacks session:read.
                    .onErrorResume(WebClientResponseException.Forbidden::class.java) { Mono.just(NO_ROLES) }
                    .flatMap { internal ->
                        organizations(token, internal.tenants).map { organizations ->
                            MeResponse(
                                user = MeUser(
                                    id = user.subject,
                                    name = user.claims["name"] as? String
                                        ?: user.claims["preferred_username"] as? String
                                        ?: user.subject,
                                    email = user.claims["email"] as? String,
                                ),
                                roles = internal.roles,
                                permissions = internal.permissions,
                                organizations = organizations,
                            )
                        }
                    }
            }
            .onErrorMap { error ->
                when {
                    error is ResponseStatusException || error is MeFailure -> error
                    // The refresh token or the Keycloak SSO session expired, or the app refused the token:
                    // 401 sends the shell back to login instead of the error page.
                    error is OAuth2AuthorizationException ||
                        error is WebClientResponseException.Unauthorized ->
                        ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired", error)
                    else -> MeFailure("the application did not answer /internal/me", error)
                }
            }

    private fun organizations(token: String, tenantIds: List<String>): Mono<List<MeOrganization>> =
        Flux.fromIterable(tenantIds)
            // Parallel but bounded, and flatMapSequential keeps the app's tenant order in the answer.
            .flatMapSequential({ tenantId ->
                app.organization(token, tenantId)
                    .map { MeOrganization(tenantId, tenantId, it.name) }
                    // The profile endpoint is still being built (#93/#94): a login must not depend on it.
                    .onErrorResume { error ->
                        if (error !is WebClientResponseException ||
                            error.statusCode.value() !in setOf(404, 501)
                        ) {
                            log.warn(
                                "Organization {} profile lookup failed: {}",
                                tenantId, (error as? WebClientResponseException)?.statusCode ?: error.message,
                                error,
                            )
                        }
                        Mono.just(MeOrganization(tenantId, tenantId, tenantId))
                    }
            }, ORGANIZATION_CONCURRENCY)
            .collectList()
}

/** The application was unreachable or answered garbage; the shell shows an error, not a login loop. */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
class MeFailure(message: String, cause: Throwable) : RuntimeException(message, cause)
