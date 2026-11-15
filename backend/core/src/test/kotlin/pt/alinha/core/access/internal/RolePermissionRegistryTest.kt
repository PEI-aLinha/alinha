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

        listOf("consultant", "responsible", "analyst").forEach {
            assertThat(registry.permissionsFor(setOf(it))).contains("plugins:read")
        }
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
}
