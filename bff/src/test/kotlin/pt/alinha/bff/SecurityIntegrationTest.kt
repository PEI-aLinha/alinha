package pt.alinha.bff

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityIntegrationTest {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var routes: RouteLocator

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
        client.get().uri("/actuator/health").exchange()
            .expectStatus().isOk
    }

    @Test
    fun `the app route sends api calls to the application`() {
        val route = routes.routes.filter { it.id == "app" }.blockFirst()
        assertThat(route).isNotNull
        assertThat(route!!.uri.toString()).isEqualTo("http://localhost:8080")
    }
}
