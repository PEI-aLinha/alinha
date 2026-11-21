package pt.alinha.core.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
        // By default every declared permission is reachable in both areas with a menu.
        rolePermissions: Map<String, Set<String>> = mapOf("responsible" to permissions, "consultant" to permissions),
    ) = object : AlinhaPlugin {
        override val manifest = PluginManifest(
            id = id,
            name = id,
            kind = PluginKind.FEATURE,
            version = "1.0.0",
            permissions = permissions,
            rolePermissions = rolePermissions.filterValues { it.isNotEmpty() },
            nav = nav,
        )
    }

    private fun registry(vararg plugins: AlinhaPlugin) = PluginRegistry(plugins.toList())

    @Test
    fun `accepts valid manifests and sorts them by id`() {
        val registry = registry(
            plugin("risk-assets", setOf("risk:read"), listOf(NavEntry("Riscos", "risks", NavArea.CLIENT, "risk"))),
            plugin("assessment", nav = listOf(NavEntry("Auditoria", "audit", NavArea.CLIENT, "audit"))),
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
        val nav = listOf(NavEntry("A", "a", NavArea.CLIENT, "a", "edit"))

        assertThatThrownBy { registry(plugin("a", setOf("a:read"), nav)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("a:edit")
    }

    @Test
    fun `menu entry may use a permission declared by another plugin`() {
        val registry = registry(
            plugin("a", setOf("a:read")),
            plugin("b", nav = listOf(NavEntry("B", "b", NavArea.CONSULTANT, "a"))),
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
            .hasMessageContaining("without label")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "/assets", "assets/", "Assets", "risk assets", "../admin", "risks//assets", "risks_assets"])
    fun `fails on menu path that is not relative kebab-case`(path: String) {
        assertThatThrownBy { registry(plugin("a", nav = listOf(NavEntry("A", path, NavArea.CLIENT)))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("menu path")
    }

    @ParameterizedTest
    @ValueSource(strings = ["organization", "risk-assets", "risks/assets", "reports/2026"])
    fun `accepts relative kebab-case menu paths`(path: String) {
        assertThat(registry(plugin("a", nav = listOf(NavEntry("A", path, NavArea.CLIENT)))).manifests).hasSize(1)
    }

    @Test
    fun `menu entry defaults match the frontend NavItem`() {
        val entry = NavEntry("A", "a", NavArea.CLIENT, "a")

        assertThat(entry.action).isEqualTo("read")
        assertThat(entry.order).isEqualTo(100)
        assertThat(entry.permission).isEqualTo("a:read")
        assertThat(NavEntry("A", "a", NavArea.CLIENT).permission).isNull()
    }

    @Test
    fun `fails on a declared permission no role is granted`() {
        assertThatThrownBy { registry(plugin("risk-assets", setOf("risk:read", "risk:edit"), rolePermissions = mapOf("responsible" to setOf("risk:read")))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no role is granted")
            .hasMessageContaining("risk:edit")
    }

    @Test
    fun `fails on a menu entry no role of its area can see`() {
        // Only analysts and responsibles hold it, but the entry is in the consultant area.
        val nav = listOf(NavEntry("Riscos", "risks", NavArea.CONSULTANT, "risk"))
        assertThatThrownBy { registry(plugin("risk-assets", setOf("risk:read"), nav, mapOf("responsible" to setOf("risk:read")))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no role that opens the consultant area holds it")
    }

    @Test
    fun `fails on a menu entry in the onboarding area`() {
        assertThatThrownBy { registry(plugin("organization", nav = listOf(NavEntry("Registo", "organization", NavArea.ONBOARDING)))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("onboarding area, which has no menu")
    }

    @Test
    fun `a menu entry with an action but no resource is refused`() {
        assertThatThrownBy { NavEntry("Riscos", "risks", NavArea.CLIENT, action = "edit") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no resource")
    }
}
