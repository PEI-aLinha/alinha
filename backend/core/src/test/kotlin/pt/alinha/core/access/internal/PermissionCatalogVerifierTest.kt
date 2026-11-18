package pt.alinha.core.access.internal

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.method.HandlerMethod
import pt.alinha.core.access.RequiresPermission
import pt.alinha.core.registry.PluginRegistry
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

class PermissionCatalogVerifierTest {
    private val plugin = object : AlinhaPlugin {
        override val manifest = PluginManifest("a", "A", PluginKind.FEATURE, "1.0.0", permissions = setOf("a:read"))
    }
    private val verifier = PermissionCatalogVerifier(RolePermissionRegistry(PluginRegistry(listOf(plugin))))

    private fun handler(name: String) = HandlerMethod(Endpoints(), Endpoints::class.java.getMethod(name))

    @Test
    fun `accepts plugin, core and unannotated permissions`() {
        assertThatCode { verifier.verify(listOf(handler("pluginRead"), handler("plugins"), handler("audit"), handler("open"))) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `fails on permission nobody declares`() {
        assertThatThrownBy { verifier.verify(listOf(handler("pluginRead"), handler("typo"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("a:raed")
    }

    @Suppress("unused")
    class Endpoints {
        @RequiresPermission("a") fun pluginRead() = Unit
        @RequiresPermission("plugins") fun plugins() = Unit
        @RequiresPermission("audit") fun audit() = Unit
        @RequiresPermission("a", "raed") fun typo() = Unit
        fun open() = Unit
    }
}
