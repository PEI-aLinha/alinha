package pt.alinha.core.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pt.alinha.pluginapi.NavArea
import pt.alinha.pluginapi.NavEntry
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

class PluginViewTest {
    private fun manifest(vararg nav: NavEntry) =
        PluginManifest("a", "A", PluginKind.FEATURE, "1.0.0", permissions = setOf("a:read"), nav = nav.toList())

    @Test
    fun `sorts by area, then order, then label in Portuguese collation`() {
        val view = PluginView.of(
            manifest(
                NavEntry("Zona", "zone", NavArea.CLIENT),
                NavEntry("Ética", "ethics", NavArea.CLIENT),
                NavEntry("Primeiro", "first", NavArea.CLIENT, order = 1),
                NavEntry("Carteira", "portfolio", NavArea.CONSULTANT, order = 0),
            ),
            granted = emptySet(),
            areas = NavArea.entries.toSet(),
        )

        assertThat(view.nav.map { it.path }).containsExactly("first", "ethics", "zone", "portfolio")
    }

    @Test
    fun `keeps only entries whose permission is granted`() {
        val view = PluginView.of(
            manifest(NavEntry("Ver", "view", NavArea.CLIENT, "a"), NavEntry("Editar", "edit", NavArea.CLIENT, "a", "edit")),
            granted = setOf("a:read"),
            areas = setOf(NavArea.CLIENT),
        )

        assertThat(view.nav.map { it.path }).containsExactly("view")
        assertThat(view.nav.single().resource).isEqualTo("a")
        assertThat(view.nav.single().action).isEqualTo("read")
    }

    @Test
    fun `keeps only entries in an area the caller opens, even without a permission`() {
        val view = PluginView.of(
            manifest(
                NavEntry("Cliente", "client", NavArea.CLIENT),
                NavEntry("Carteira", "portfolio", NavArea.CONSULTANT),
                NavEntry("Registo", "register", NavArea.ONBOARDING),
            ),
            granted = setOf("a:read"),
            areas = setOf(NavArea.ONBOARDING),
        )

        assertThat(view.nav.map { it.path }).containsExactly("register")
    }
}
