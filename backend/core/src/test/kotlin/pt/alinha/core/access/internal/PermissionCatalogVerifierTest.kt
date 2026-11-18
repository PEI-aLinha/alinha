package pt.alinha.core.access.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.config.annotation.EnableWebMvc
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

    @Test
    fun `a running context with an unknown permission fails to start`() {
        contextWith(TypoController::class.java).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasMessageContaining("a:raed")
        }
    }

    @Test
    fun `a running context with known permissions starts`() {
        contextWith(KnownController::class.java).run { context -> assertThat(context).hasNotFailed() }
    }

    private fun contextWith(controller: Class<*>) = WebApplicationContextRunner()
        .withUserConfiguration(WebConfig::class.java, controller)
        .withBean(AlinhaPlugin::class.java, { plugin })
        .withBean(PluginRegistry::class.java)
        .withBean(RolePermissionRegistry::class.java)
        .withBean(PermissionCatalogVerifier::class.java)

    @Configuration
    @EnableWebMvc
    class WebConfig

    @RestController
    class TypoController {
        @RequiresPermission("a", "raed")
        @GetMapping("/typo")
        fun typo() = "ok"
    }

    @RestController
    class KnownController {
        @RequiresPermission("a")
        @GetMapping("/known")
        fun known() = "ok"
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
