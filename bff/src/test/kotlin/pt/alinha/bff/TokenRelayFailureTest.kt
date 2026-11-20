package pt.alinha.bff

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.ClientAuthorizationException
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono

@SpringBootTest
class TokenRelayFailureTest {

    @Autowired
    lateinit var context: ApplicationContext

    @MockitoBean
    lateinit var clientManager: ReactiveOAuth2AuthorizedClientManager

    @Test
    fun `a rejected refresh token on a relayed call answers 401 JSON, not 500`() {
        Mockito.`when`(clientManager.authorize(Mockito.any<OAuth2AuthorizeRequest>()))
            .thenReturn(Mono.error(ClientAuthorizationException(OAuth2Error("invalid_grant"), "keycloak")))

        WebTestClient.bindToApplicationContext(context)
            .apply(springSecurity())
            .apply(mockOidcLogin())
            .build()
            .get().uri("/api/organization/assets").exchange()
            .expectStatus().isUnauthorized
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.title").isEqualTo("Unauthorized")
            .jsonPath("$.status").isEqualTo(401)
    }
}
