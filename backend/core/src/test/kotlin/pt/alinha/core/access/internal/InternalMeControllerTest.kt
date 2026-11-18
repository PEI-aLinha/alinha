package pt.alinha.core.access.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import pt.alinha.core.registry.PluginRegistry

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
    }
}
