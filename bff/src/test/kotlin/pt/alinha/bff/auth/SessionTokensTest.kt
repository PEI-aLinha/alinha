package pt.alinha.bff.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.time.Instant

/** The tokens must live in the (Redis) WebSession so every BFF instance sees them. */
@SpringBootTest
class SessionTokensTest {

    @Autowired
    lateinit var clients: ServerOAuth2AuthorizedClientRepository

    @Autowired
    lateinit var registrations: ReactiveClientRegistrationRepository

    @Test
    fun `the authorized client is stored in the WebSession and serializes for Redis`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/me").build())
        val principal = UsernamePasswordAuthenticationToken.authenticated("rui", null, emptyList())
        val client = OAuth2AuthorizedClient(
            registrations.findByRegistrationId("keycloak").block()!!,
            "rui",
            OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access", Instant.now(), Instant.now().plusSeconds(60)),
            OAuth2RefreshToken("refresh", Instant.now()),
        )

        clients.saveAuthorizedClient(client, principal, exchange).block()

        val attributes = exchange.session.block()!!.attributes
        assertThat(attributes.values).anySatisfy { value ->
            assertThat(value).isInstanceOf(Map::class.java)
            assertThat((value as Map<*, *>).values).contains(client)
        }
        // Spring Session's Redis store uses JDK serialization by default.
        attributes.values.forEach { ObjectOutputStream(ByteArrayOutputStream()).writeObject(it) }
        assertThat(clients.loadAuthorizedClient<OAuth2AuthorizedClient>("keycloak", principal, exchange).block())
            .isEqualTo(client)
    }
}
