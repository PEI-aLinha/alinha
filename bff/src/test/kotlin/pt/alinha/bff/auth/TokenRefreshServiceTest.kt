package pt.alinha.bff.auth

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.client.OAuth2AuthorizationContext
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.RefreshTokenReactiveOAuth2AuthorizedClientProvider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.web.server.ResponseStatusException
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Instant

class TokenRefreshServiceTest {

    private val authentication = UsernamePasswordAuthenticationToken("rui", null, emptyList())
    private val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/refresh").build())
    private val clients = mock(ServerOAuth2AuthorizedClientRepository::class.java)
    private val provider = mock(RefreshTokenReactiveOAuth2AuthorizedClientProvider::class.java)
    private val service = TokenRefreshService(clients, provider, BffProperties())

    private val registration = ClientRegistration.withRegistrationId("keycloak")
        .clientId("bff")
        .clientSecret("secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("http://localhost:8081/login/oauth2/code/keycloak")
        .authorizationUri("http://keycloak/auth")
        .tokenUri("http://keycloak/token")
        .build()

    private fun client(refreshToken: OAuth2RefreshToken?) = OAuth2AuthorizedClient(
        registration,
        "rui",
        OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "old", Instant.now(), Instant.now().plusSeconds(60)),
        refreshToken,
    )

    private fun givenSession(refreshToken: OAuth2RefreshToken?) {
        Mockito.`when`(clients.loadAuthorizedClient<OAuth2AuthorizedClient>("keycloak", authentication, exchange))
            .thenReturn(Mono.just(client(refreshToken)))
    }

    @Test
    fun `replaces the stored client with the refreshed one`() {
        val refreshToken = OAuth2RefreshToken("refresh", Instant.now())
        givenSession(refreshToken)
        val refreshed = client(refreshToken)
        Mockito.`when`(provider.authorize(Mockito.any<OAuth2AuthorizationContext>()))
            .thenReturn(Mono.just(refreshed))
        Mockito.`when`(clients.saveAuthorizedClient(refreshed, authentication, exchange)).thenReturn(Mono.empty())

        StepVerifier.create(service.refresh(exchange, authentication)).verifyComplete()

        Mockito.verify(clients).saveAuthorizedClient(refreshed, authentication, exchange)
    }

    @Test
    fun `a session without a refresh token cannot refresh`() {
        givenSession(null)

        StepVerifier.create(service.refresh(exchange, authentication))
            .expectErrorMatches { it is ResponseStatusException && it.statusCode == HttpStatus.UNAUTHORIZED }
            .verify()
    }

    @Test
    fun `no stored client is 401`() {
        Mockito.`when`(clients.loadAuthorizedClient<OAuth2AuthorizedClient>("keycloak", authentication, exchange))
            .thenReturn(Mono.empty())

        StepVerifier.create(service.refresh(exchange, authentication))
            .expectErrorMatches { it is ResponseStatusException && it.statusCode == HttpStatus.UNAUTHORIZED }
            .verify()
    }
}
