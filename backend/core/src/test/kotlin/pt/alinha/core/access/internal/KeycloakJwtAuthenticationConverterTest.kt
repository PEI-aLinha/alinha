package pt.alinha.core.access.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt

class KeycloakJwtAuthenticationConverterTest {
    private val converter = KeycloakJwtAuthenticationConverter()

    private fun jwt(vararg claims: Pair<String, Any>): Jwt =
        Jwt.withTokenValue("token").header("alg", "none").subject("user-1").claims { it.putAll(claims) }.build()

    private fun authorities(jwt: Jwt) = converter.convert(jwt).authorities.map { it.authority }

    @Test
    fun `maps realm roles to role authorities`() {
        val token = jwt("realm_access" to mapOf("roles" to listOf("analyst", "consultant")))

        assertThat(authorities(token)).containsExactlyInAnyOrder("ROLE_analyst", "ROLE_consultant")
    }

    @Test
    fun `gives no authorities when realm_access is absent`() {
        assertThat(authorities(jwt("other" to "value"))).isEmpty()
    }

    @Test
    fun `gives no authorities when realm_access is malformed`() {
        assertThat(authorities(jwt("realm_access" to "analyst"))).isEmpty()
        assertThat(authorities(jwt("realm_access" to mapOf("roles" to "analyst")))).isEmpty()
        assertThat(authorities(jwt("realm_access" to mapOf("roles" to listOf(1, "analyst")))))
            .containsExactly("ROLE_analyst")
    }
}
