package pt.alinha.bff

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest
class CsrfIntegrationTest {

    @Autowired
    lateinit var context: ApplicationContext

    private val client: WebTestClient
        get() = WebTestClient.bindToApplicationContext(context)
            .apply(springSecurity())
            .apply(mockUser())
            .build()

    @Test
    fun `an authenticated write with the cookie and X-XSRF-TOKEN is not rejected`() {
        val token = csrfToken()

        client.post().uri("/csrf-test")
            .cookie("XSRF-TOKEN", token)
            .header("X-XSRF-TOKEN", token)
            .exchange()
            .expectStatus().value { assertThat(it).isNotEqualTo(403) }
    }

    @Test
    fun `an authenticated write without X-XSRF-TOKEN is 403`() {
        val token = csrfToken()

        client.post().uri("/csrf-test")
            .cookie("XSRF-TOKEN", token)
            .exchange()
            .expectStatus().isForbidden
    }

    /** A GET materialises the double-submit cookie, exactly as the shell's first `/api/me` call does. */
    private fun csrfToken(): String =
        client.get().uri("/actuator/health").exchange()
            .expectStatus().isOk
            .expectCookie().exists("XSRF-TOKEN")
            .returnResult(String::class.java)
            .responseCookies.getFirst("XSRF-TOKEN")!!
            .value
}
