package pt.alinha.bff.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockOidcLogin
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity
import org.springframework.session.ReactiveMapSessionRepository
import org.springframework.session.Session
import org.springframework.session.config.annotation.web.server.EnableSpringWebSession
import org.springframework.test.web.reactive.server.WebTestClient
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The tokens must live in the (Redis) WebSession so every BFF instance sees them and logout deletes them. */
@SpringBootTest
class SessionTokensTest {

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var clients: ServerOAuth2AuthorizedClientRepository

    @Autowired
    lateinit var registrations: ReactiveClientRegistrationRepository

    @Autowired
    lateinit var sessionRepository: ReactiveMapSessionRepository

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

    @Test
    fun `logout deletes the session`() {
        STORED_SESSIONS.clear()
        val session = sessionRepository.createSession().block()!!
        sessionRepository.save(session).block()
        val client = WebTestClient.bindToApplicationContext(context)
            .apply(springSecurity())
            .apply(mockOidcLogin())
            .build()
        val csrfToken = client.get().uri("/actuator/health").exchange()
            .returnResult(String::class.java)
            .responseCookies.getFirst("XSRF-TOKEN")!!
            .value

        client.post().uri("/auth/logout")
            .cookie("ALINHA_SESSION", session.id)
            .cookie("XSRF-TOKEN", csrfToken)
            .header("X-XSRF-TOKEN", csrfToken)
            .exchange()
            .expectStatus().isFound

        // Not just the old id: logout renames the session (changeSessionId), so check that no session is left at all.
        assertThat(STORED_SESSIONS).isEmpty()
    }

    /** No Redis in the tests: the WebSession store is an in-memory map, replacing the Redis repository. */
    @TestConfiguration
    @EnableSpringWebSession
    class InMemorySessions {
        @Bean
        fun sessionRepository() = ReactiveMapSessionRepository(STORED_SESSIONS)
    }

    private companion object {
        val STORED_SESSIONS = ConcurrentHashMap<String, Session>()
    }
}
