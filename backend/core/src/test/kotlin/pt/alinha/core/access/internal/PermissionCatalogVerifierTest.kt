package pt.alinha.core.access.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.web.ErrorProperties
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController
import org.springframework.boot.webmvc.error.DefaultErrorAttributes
import org.springframework.boot.webmvc.error.ErrorController
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
        override val manifest = PluginManifest(
            "a", "A", PluginKind.FEATURE, "1.0.0",
            permissions = setOf("a:read"), rolePermissions = mapOf("analyst" to setOf("a:read")),
        )
    }
    private val verifier = PermissionCatalogVerifier(RolePermissionRegistry(PluginRegistry(listOf(plugin))))

    private fun handler(name: String) = HandlerMethod(Endpoints(), Endpoints::class.java.getMethod(name))

    @Test
    fun `accepts plugin and core permissions`() {
        assertThatCode { verifier.verify(listOf(handler("pluginRead"), handler("plugins"), handler("audit"))) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `fails on endpoint without RequiresPermission`() {
        assertThatThrownBy { verifier.verify(listOf(handler("pluginRead"), handler("open"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("without @RequiresPermission")
            .hasMessageContaining("open")
    }

    @Test
    fun `exempts Spring's error controller`() {
        val controller = BasicErrorController(DefaultErrorAttributes(), ErrorProperties())
        val error = HandlerMethod(controller, BasicErrorController::class.java.getMethod("error", HttpServletRequest::class.java))

        assertThatCode { verifier.verify(listOf(error)) }.doesNotThrowAnyException()
    }

    @Test
    fun `another ErrorController still needs a permission, as the interceptor checks it`() {
        val error = HandlerMethod(CustomErrorEndpoint(), CustomErrorEndpoint::class.java.getMethod("error"))

        assertThatThrownBy { verifier.verify(listOf(error)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("without @RequiresPermission")
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
    class CustomErrorEndpoint : ErrorController {
        fun error() = "error"
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
