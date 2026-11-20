package pt.alinha.core.access.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pt.alinha.core.registry.PluginRegistry
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

class RolePermissionRegistryTest {
    private fun plugin(id: String, permissions: Set<String>, rolePermissions: Map<String, Set<String>>) =
        object : AlinhaPlugin {
            override val manifest = PluginManifest(
                id = id,
                name = id,
                kind = PluginKind.FEATURE,
                version = "1.0.0",
                permissions = permissions,
                rolePermissions = rolePermissions,
            )
        }

    private fun registry(vararg plugins: AlinhaPlugin) = RolePermissionRegistry(PluginRegistry(plugins.toList()))

    @Test
    fun `aggregates permissions across plugins and roles`() {
        val registry = registry(
            plugin("a", setOf("a:read", "a:edit"), mapOf("analyst" to setOf("a:read"), "responsible" to setOf("a:edit"))),
            plugin("b", setOf("b:read"), mapOf("analyst" to setOf("b:read"))),
        )

        assertThat(registry.permissionsFor(setOf("analyst"))).contains("a:read", "b:read").doesNotContain("a:edit")
        assertThat(registry.permissionsFor(setOf("analyst", "responsible"))).contains("a:read", "a:edit", "b:read")
    }

    @Test
    fun `grants core permission to every role`() {
        val registry = registry()

        listOf("consultant", "responsible", "analyst", "registrant").forEach {
            assertThat(registry.permissionsFor(setOf(it))).contains("plugins:read", "session:read")
        }
    }

    @Test
    fun `audit history is readable by consultant and responsible, not analyst`() {
        val registry = registry()

        assertThat(registry.permissionsFor(setOf("consultant"))).contains("audit:read")
        assertThat(registry.permissionsFor(setOf("responsible"))).contains("audit:read")
        assertThat(registry.permissionsFor(setOf("analyst"))).doesNotContain("audit:read")
    }

    @Test
    fun `fails when a plugin declares a core role permission`() {
        assertThatThrownBy { registry(plugin("a", setOf("audit:read"), emptyMap())) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("core permissions")
    }

    @Test
    fun `registrant gets only what plugins map to it, never audit history`() {
        val registry = registry(
            plugin("org", setOf("org:register", "org:read"), mapOf("registrant" to setOf("org:register"))),
        )

        assertThat(registry.permissionsFor(setOf("registrant"))).containsExactlyInAnyOrder("org:register", "plugins:read", "session:read")
    }

    @Test
    fun `grants nothing to unknown or missing roles`() {
        assertThat(registry().permissionsFor(setOf("intruder"))).isEmpty()
        assertThat(registry().permissionsFor(emptySet())).isEmpty()
    }

    @Test
    fun `fails on unknown role`() {
        assertThatThrownBy { registry(plugin("a", setOf("a:read"), mapOf("admin" to setOf("a:read")))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("admin")
    }

    @Test
    fun `fails on permission the manifest does not declare`() {
        assertThatThrownBy { registry(plugin("a", setOf("a:read"), mapOf("analyst" to setOf("a:edit")))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("a:edit")
    }

    @Test
    fun `fails when two plugins declare the same permission`() {
        assertThatThrownBy {
            registry(
                plugin("a", setOf("a:read"), mapOf("analyst" to setOf("a:read"))),
                plugin("b", setOf("a:read"), mapOf("consultant" to setOf("a:read"))),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("a:read")
    }

    @Test
    fun `fails when a plugin declares a core permission`() {
        assertThatThrownBy { registry(plugin("a", setOf("plugins:read"), emptyMap())) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("plugins:read")
    }

    @Test
    fun `exposes core and plugin permissions as declared`() {
        val registry = registry(plugin("a", setOf("a:read", "a:edit"), emptyMap()))

        assertThat(registry.declaredPermissions).containsExactlyInAnyOrder("plugins:read", "session:read", "audit:read", "a:read", "a:edit")
    }
}
