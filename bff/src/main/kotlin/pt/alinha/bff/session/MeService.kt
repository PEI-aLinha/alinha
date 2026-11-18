package pt.alinha.bff.session

import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

private val NIL_UUID = UUID(0, 0)

@Service
class MeService(
    private val app: AppClient,
    private val clients: ReactiveOAuth2AuthorizedClientManager,
    private val props: BffProperties,
) {

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
                    .flatMap { internal ->
                        organizations(token, user, internal.roles).map { organizations ->
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
                if (error is ResponseStatusException || error is MeFailure) error
                else MeFailure("the application did not answer /internal/me", error)
            }

    private fun organizations(
        token: String,
        user: OidcUser,
        roles: List<String>,
    ): Mono<List<MeOrganization>> =
        Flux.fromIterable(tenantIds(user.claims, roles))
            // Preserve the claim order in the answer.
            .concatMap { tenantId ->
                app.organization(token, tenantId)
                    .map { MeOrganization(tenantId, tenantId, it.name) }
                    // The profile endpoint is still being built (#93/#94): a login must not depend on it.
                    .onErrorResume { Mono.just(MeOrganization(tenantId, tenantId, tenantId)) }
            }
            .collectList()

    private fun tenantIds(claims: Map<String, Any>, roles: List<String>): List<String> {
        val raw: Collection<*> = if ("consultant" in roles) {
            (claims["tenants"] as? Collection<*>) ?: emptyList<Any>()
        } else {
            listOfNotNull(claims["tenant_id"] as? String)
        }
        return raw.mapNotNull { it as? String }
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .filter { it != NIL_UUID }
            .map(UUID::toString)
    }
}

/** The application was unreachable or answered garbage; the shell shows an error, not a login loop. */
@org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.BAD_GATEWAY)
class MeFailure(message: String, cause: Throwable) : RuntimeException(message, cause)
