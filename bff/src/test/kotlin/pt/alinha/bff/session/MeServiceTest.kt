package pt.alinha.bff.session

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.client.ClientAuthorizationException
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Instant
import java.util.UUID

class MeServiceTest {

    private val token = "access-token"
    private val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/me").build())
    private val authentication = UsernamePasswordAuthenticationToken("rui", null, emptyList())
    private val clients = mock(ReactiveOAuth2AuthorizedClientManager::class.java)
    private val app = mock(AppClient::class.java)

    private val authorizedClient = OAuth2AuthorizedClient(
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("bff")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8081/login/oauth2/code/keycloak")
            .authorizationUri("http://keycloak/auth")
            .tokenUri("http://keycloak/token")
            .build(),
        "rui",
        OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token, Instant.now(), Instant.now().plusSeconds(60)),
    )

    private val service = MeService(app, clients, BffProperties())

    private fun user(vararg claims: Pair<String, Any>): DefaultOidcUser {
        val idToken = OidcIdToken.withTokenValue("id")
            .issuer("http://keycloak/realms/alinha")
            .subject("user-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("name", "Rui Santos")
            .apply { claims.forEach { (key, value) -> claim(key, value) } }
            .build()
        return DefaultOidcUser(emptyList(), idToken)
    }

    private fun givenSession() {
        Mockito.`when`(clients.authorize(Mockito.any<OAuth2AuthorizeRequest>()))
            .thenReturn(Mono.just(authorizedClient))
    }

    @Test
    fun `composes the session from the app permissions and the organization profiles`() {
        givenSession()
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        Mockito.`when`(app.internalMe(token))
            .thenReturn(
                Mono.just(
                    InternalMe(
                        listOf("consultant"),
                        listOf("plugins:read", "session:read"),
                        listOf(first, second),
                    ),
                ),
            )
        Mockito.`when`(app.organization(token, first)).thenReturn(Mono.just(AppOrganization("Componentes Águeda")))
        Mockito.`when`(app.organization(token, second)).thenReturn(Mono.just(AppOrganization("Metalúrgica de Aveiro")))

        StepVerifier.create(service.me(exchange, authentication, user()))
            .assertNext { me ->
                assertThat(me.user).isEqualTo(MeUser("user-1", "Rui Santos", null))
                assertThat(me.roles).containsExactly("consultant")
                assertThat(me.organizations.map { it.name })
                    .containsExactly("Componentes Águeda", "Metalúrgica de Aveiro")
                assertThat(me.organizations.map { it.id }).containsExactly(first, second)
                assertThat(me.organizations).allSatisfy { assertThat(it.slug).isEqualTo(it.id) }
            }
            .verifyComplete()
    }

    @Test
    fun `after a refresh it uses the app tenants, not the stale id token claims`() {
        givenSession()
        val oldTenant = UUID.randomUUID().toString()
        val newTenant = UUID.randomUUID().toString()
        Mockito.`when`(app.internalMe(token))
            .thenReturn(Mono.just(InternalMe(listOf("responsible"), listOf("session:read"), listOf(newTenant))))
        Mockito.`when`(app.organization(token, newTenant)).thenReturn(Mono.just(AppOrganization("Nova Águeda")))

        StepVerifier.create(service.me(exchange, authentication, user("tenant_id" to oldTenant)))
            .assertNext { me ->
                assertThat(me.organizations.map { it.id }).containsExactly(newTenant)
                assertThat(me.organizations.map { it.name }).containsExactly("Nova Águeda")
            }
            .verifyComplete()
    }

    @Test
    fun `falls back to the tenant id when the profile endpoint is not available`() {
        givenSession()
        val tenant = UUID.randomUUID().toString()
        Mockito.`when`(app.internalMe(token))
            .thenReturn(Mono.just(InternalMe(listOf("responsible"), listOf("session:read"), listOf(tenant))))
        Mockito.`when`(app.organization(token, tenant))
            .thenReturn(Mono.error(notFound()))

        StepVerifier.create(service.me(exchange, authentication, user()))
            .assertNext { me ->
                assertThat(me.organizations).containsExactly(MeOrganization(tenant, tenant, tenant))
            }
            .verifyComplete()
    }

    @Test
    fun `a non-404 profile error is still swallowed for the login to work`() {
        givenSession()
        val tenant = UUID.randomUUID().toString()
        Mockito.`when`(app.internalMe(token))
            .thenReturn(Mono.just(InternalMe(listOf("responsible"), listOf("session:read"), listOf(tenant))))
        Mockito.`when`(app.organization(token, tenant))
            .thenReturn(Mono.error(serverError()))

        StepVerifier.create(service.me(exchange, authentication, user()))
            .assertNext { me ->
                assertThat(me.organizations).containsExactly(MeOrganization(tenant, tenant, tenant))
            }
            .verifyComplete()
    }

    @Test
    fun `a registrant with no organization gets an empty list`() {
        givenSession()
        Mockito.`when`(app.internalMe(token))
            .thenReturn(Mono.just(InternalMe(listOf("registrant"), listOf("session:read"), emptyList())))

        StepVerifier.create(service.me(exchange, authentication, user()))
            .assertNext { assertThat(it.organizations).isEmpty() }
            .verifyComplete()
    }

    @Test
    fun `without a stored authorized client the answer is 401`() {
        Mockito.`when`(clients.authorize(Mockito.any<OAuth2AuthorizeRequest>()))
            .thenReturn(Mono.empty())

        StepVerifier.create(service.me(exchange, authentication, user()))
            .expectErrorMatches { it is ResponseStatusException && it.statusCode == HttpStatus.UNAUTHORIZED }
            .verify()
    }

    @Test
    fun `a token with no known role is a session without roles, not an error`() {
        givenSession()
        Mockito.`when`(app.internalMe(token)).thenReturn(Mono.error(status(403)))

        StepVerifier.create(service.me(exchange, authentication, user()))
            .assertNext { me ->
                assertThat(me.roles).isEmpty()
                assertThat(me.permissions).isEmpty()
                assertThat(me.organizations).isEmpty()
            }
            .verifyComplete()
    }

    @Test
    fun `an expired refresh token or SSO session is 401, so the shell goes to login`() {
        Mockito.`when`(clients.authorize(Mockito.any<OAuth2AuthorizeRequest>()))
            .thenReturn(Mono.error(ClientAuthorizationException(OAuth2Error("invalid_grant"), "keycloak")))

        StepVerifier.create(service.me(exchange, authentication, user()))
            .expectErrorMatches { it is ResponseStatusException && it.statusCode == HttpStatus.UNAUTHORIZED }
            .verify()
    }

    @Test
    fun `a token the app refuses is 401`() {
        givenSession()
        Mockito.`when`(app.internalMe(token)).thenReturn(Mono.error(status(401)))

        StepVerifier.create(service.me(exchange, authentication, user()))
            .expectErrorMatches { it is ResponseStatusException && it.statusCode == HttpStatus.UNAUTHORIZED }
            .verify()
    }

    @Test
    fun `an app failure is still 502`() {
        givenSession()
        Mockito.`when`(app.internalMe(token)).thenReturn(Mono.error(serverError()))

        StepVerifier.create(service.me(exchange, authentication, user()))
            .expectError(MeFailure::class.java)
            .verify()
    }

    private fun status(code: Int) =
        WebClientResponseException.create(code, "status $code", HttpHeaders.EMPTY, ByteArray(0), null)

    private fun notFound() =
        WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, ByteArray(0), null)

    private fun serverError() =
        WebClientResponseException.create(500, "Server Error", HttpHeaders.EMPTY, ByteArray(0), null)
}
