package pt.alinha.core.access.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import pt.alinha.core.registry.PluginRegistry
import java.util.UUID

class InternalMeControllerTest {

    private val controller = InternalMeController(RolePermissionRegistry(PluginRegistry(emptyList())))

    private fun authentication(vararg roles: String) = UsernamePasswordAuthenticationToken(
        "user",
        null,
        roles.map { SimpleGrantedAuthority(ROLE_PREFIX + it) },
    )

    @Test
    fun `answers the known roles and the permissions they grant`() {
        val me = controller.me(authentication("consultant", "default-roles-alinha"))

        assertThat(me.roles).containsExactly("consultant")
        assertThat(me.permissions).contains("plugins:read", "session:read")
    }

    @Test
    fun `a registrant is a known role and can read its own session`() {
        val me = controller.me(authentication("registrant"))

        assertThat(me.roles).containsExactly("registrant")
        assertThat(me.permissions).contains("session:read")
    }

    @Test
    fun `an unknown role grants nothing`() {
        val me = controller.me(authentication("intruder"))

        assertThat(me.roles).isEmpty()
        assertThat(me.permissions).isEmpty()
        assertThat(me.tenants).isEmpty()
    }

    @Test
    fun `answers the organizations validated from the access token`() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
            .claim("tenants", listOf(first, "not-a-uuid", second))
            .build()
        val auth = JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority(ROLE_PREFIX + "consultant")))

        val me = controller.me(auth)

        assertThat(me.tenants).containsExactly(first, second)
    }

    @Test
    fun `a client user gets only its own tenant`() {
        val own = UUID.randomUUID().toString()
        val jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
            .claim("tenant_id", own)
            .build()
        val auth = JwtAuthenticationToken(jwt, listOf(SimpleGrantedAuthority(ROLE_PREFIX + "responsible")))

        assertThat(controller.me(auth).tenants).containsExactly(own)
    }
}
