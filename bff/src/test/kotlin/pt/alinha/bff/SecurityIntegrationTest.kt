package pt.alinha.bff

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.server.adapter.ForwardedHeaderTransformer

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityIntegrationTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var routes: RouteLocator

    @Autowired
    lateinit var context: ApplicationContext

    private val client: WebTestClient
        get() = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()

    @Test
    fun `api me without a session answers 401 JSON, never a redirect`() {
        client.get().uri("/api/me").exchange()
            .expectStatus().isUnauthorized
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.status").isEqualTo(401)
    }

    @Test
    fun `a browser page without a session goes to the Keycloak login`() {
        client.get().uri("/").exchange()
            .expectStatus().isFound
            .expectHeader().valueEquals("Location", "/oauth2/authorization/keycloak")
    }

    @Test
    fun `the health check stays public`() {
        client.get().uri("/actuator/health/liveness").exchange()
            .expectStatus().isOk
    }

    /** Outside the prod profile a spoofed X-Forwarded-For must not change the rate-limit key (the remote address). */
    @Test
    fun `forwarded headers are not trusted outside prod`() {
        assertThat(context.getBeanProvider(ForwardedHeaderTransformer::class.java).ifAvailable).isNull()
    }

    @Test
    fun `the app route sends api calls to the application`() {
        val route = routes.routes.filter { it.id == "app" }.blockFirst()
        assertThat(route).isNotNull
        assertThat(route!!.uri.toString()).isEqualTo("http://localhost:8080")
    }

    /** The app uses bearer auth: the browser's session cookie must not reach it. */
    @Test
    fun `the app route strips the Cookie header`() {
        val route = routes.routes.filter { it.id == "app" }.blockFirst()!!
        assertThat(route.filters.map { it.toString() })
            .anySatisfy { assertThat(it).contains("RemoveRequestHeader").contains("Cookie") }
    }
}
