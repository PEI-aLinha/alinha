package pt.alinha.bff.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.client.ClientAuthorizationException
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse
import org.springframework.web.server.ResponseStatusException
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Instant

class TokenRefreshServiceTest {

    private val authentication = UsernamePasswordAuthenticationToken("rui", null, emptyList())
    private val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/refresh").build())
    private val clients = mock(ServerOAuth2AuthorizedClientRepository::class.java)
    @Suppress("UNCHECKED_CAST")
    private val tokenClient =
        mock(ReactiveOAuth2AccessTokenResponseClient::class.java)
            as ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest>
    private val service = TokenRefreshService(clients, tokenClient, BffProperties())

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

    /** The access token in the session is still valid for 60 s: the refresh must happen anyway. */
    @Test
    fun `refreshes a still valid token and stores the new one`() {
        val refreshToken = OAuth2RefreshToken("refresh", Instant.now())
        givenSession(refreshToken)
        givenTokenResponse(OAuth2AccessTokenResponse.withToken("new").tokenType(OAuth2AccessToken.TokenType.BEARER)
            .expiresIn(300).refreshToken("rotated").build())
        val saved = ArgumentCaptor.forClass(OAuth2AuthorizedClient::class.java)
        Mockito.`when`(clients.saveAuthorizedClient(saved.capture(), Mockito.eq(authentication), Mockito.eq(exchange)))
            .thenReturn(Mono.empty())

        StepVerifier.create(service.refresh(exchange, authentication)).verifyComplete()

        assertThat(saved.value.accessToken.tokenValue).isEqualTo("new")
        assertThat(saved.value.refreshToken?.tokenValue).isEqualTo("rotated")
        assertThat(saved.value.principalName).isEqualTo("rui")
    }

    @Test
    fun `keeps the current refresh token when Keycloak does not rotate it`() {
        givenSession(OAuth2RefreshToken("refresh", Instant.now()))
        givenTokenResponse(OAuth2AccessTokenResponse.withToken("new").tokenType(OAuth2AccessToken.TokenType.BEARER)
            .expiresIn(300).build())
        val saved = ArgumentCaptor.forClass(OAuth2AuthorizedClient::class.java)
        Mockito.`when`(clients.saveAuthorizedClient(saved.capture(), Mockito.eq(authentication), Mockito.eq(exchange)))
            .thenReturn(Mono.empty())

        StepVerifier.create(service.refresh(exchange, authentication)).verifyComplete()

        assertThat(saved.value.refreshToken?.tokenValue).isEqualTo("refresh")
    }

    @Test
    fun `an expired refresh token or SSO session is 401`() {
        givenSession(OAuth2RefreshToken("refresh", Instant.now()))
        Mockito.`when`(tokenClient.getTokenResponse(Mockito.any()))
            .thenReturn(Mono.error(ClientAuthorizationException(OAuth2Error("invalid_grant"), "keycloak")))

        StepVerifier.create(service.refresh(exchange, authentication))
            .expectErrorMatches { it is ResponseStatusException && it.statusCode == HttpStatus.UNAUTHORIZED }
            .verify()
    }

    private fun givenTokenResponse(response: OAuth2AccessTokenResponse) {
        Mockito.`when`(tokenClient.getTokenResponse(Mockito.any())).thenReturn(Mono.just(response))
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
