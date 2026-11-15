package pt.alinha.core.access.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.web.method.HandlerMethod
import pt.alinha.core.access.RequiresPermission

class RequiredPermissionsVerifierTest {
    @Suppress("unused")
    private class Endpoints {
        @RequiresPermission("assessment")
        fun declared() = "ok"

        @RequiresPermission("assesment")
        fun typo() = "ok"

        fun unannotated() = "ok"
    }

    @Suppress("unused")
    private class SpringErrorEndpoint : ErrorController {
        fun error() = "error"
    }

    private fun handler(name: String) = HandlerMethod(Endpoints(), Endpoints::class.java.getDeclaredMethod(name))

    @Test
    fun `reports an endpoint whose permission no manifest declares`() {
        val undeclared = undeclaredPermissions(listOf(handler("declared"), handler("typo")), setOf("assessment:read"))

        assertThat(undeclared.values).containsExactly("assesment:read")
    }

    @Test
    fun `undeclared check ignores declared and unannotated endpoints`() {
        val handlers = listOf(handler("declared"), handler("unannotated"))

        assertThat(undeclaredPermissions(handlers, setOf("assessment:read"))).isEmpty()
    }

    @Test
    fun `reports an endpoint without RequiresPermission`() {
        val unannotated = unannotatedEndpoints(listOf(handler("declared"), handler("unannotated")))

        assertThat(unannotated).hasSize(1).first().asString().contains("unannotated")
    }

    @Test
    fun `exempts the error controller`() {
        val errorHandler = HandlerMethod(SpringErrorEndpoint(), SpringErrorEndpoint::class.java.getDeclaredMethod("error"))

        assertThat(unannotatedEndpoints(listOf(errorHandler))).isEmpty()
    }
}
