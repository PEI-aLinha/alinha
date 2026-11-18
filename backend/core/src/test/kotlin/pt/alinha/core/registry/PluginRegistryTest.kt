package pt.alinha.core.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.NavArea
import pt.alinha.pluginapi.NavEntry
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

class PluginRegistryTest {
    private fun plugin(
        id: String,
        permissions: Set<String> = emptySet(),
        nav: List<NavEntry> = emptyList(),
    ) = object : AlinhaPlugin {
        override val manifest = PluginManifest(
            id = id,
            name = id,
            kind = PluginKind.FEATURE,
            version = "1.0.0",
            permissions = permissions,
            nav = nav,
        )
    }

    private fun registry(vararg plugins: AlinhaPlugin) = PluginRegistry(plugins.toList())

    @Test
    fun `accepts valid manifests and sorts them by id`() {
        val registry = registry(
            plugin("risk-assets", setOf("risk:read"), listOf(NavEntry("Riscos", "risks", NavArea.CLIENT, "risk:read"))),
            plugin("assessment", nav = listOf(NavEntry("Auditoria", "audit", NavArea.CLIENT, "audit:read"))),
        )

        assertThat(registry.manifests.map { it.id }).containsExactly("assessment", "risk-assets")
    }

    @Test
    fun `fails on duplicate plugin ids`() {
        assertThatThrownBy { registry(plugin("a"), plugin("a")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Duplicate plugin ids")
    }

    @Test
    fun `fails on id that is not kebab-case`() {
        assertThatThrownBy { registry(plugin("Risk_Assets")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Risk_Assets")
    }

    @Test
    fun `fails on permission not in resource action form`() {
        assertThatThrownBy { registry(plugin("a", setOf("read"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("resource:action")
    }

    @Test
    fun `fails on menu entry behind an unknown permission`() {
        val nav = listOf(NavEntry("A", "a", NavArea.CLIENT, "a:edit"))

        assertThatThrownBy { registry(plugin("a", setOf("a:read"), nav)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("a:edit")
    }

    @Test
    fun `menu entry may use a permission declared by another plugin`() {
        val registry = registry(
            plugin("a", setOf("a:read")),
            plugin("b", nav = listOf(NavEntry("B", "b", NavArea.CONSULTANT, "a:read"))),
        )

        assertThat(registry.manifests).hasSize(2)
    }

    @Test
    fun `fails on two menu entries with the same area and path`() {
        assertThatThrownBy {
            registry(
                plugin("a", nav = listOf(NavEntry("A", "shared", NavArea.CLIENT))),
                plugin("b", nav = listOf(NavEntry("B", "shared", NavArea.CLIENT))),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("client/shared")
    }

    @Test
    fun `same path in different areas is allowed`() {
        val registry = registry(
            plugin("a", nav = listOf(NavEntry("A", "shared", NavArea.CLIENT))),
            plugin("b", nav = listOf(NavEntry("B", "shared", NavArea.CONSULTANT))),
        )

        assertThat(registry.manifests).hasSize(2)
    }

    @Test
    fun `fails on menu entry without label`() {
        assertThatThrownBy { registry(plugin("a", nav = listOf(NavEntry(" ", "a", NavArea.CLIENT)))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("without label or path")
    }
}
