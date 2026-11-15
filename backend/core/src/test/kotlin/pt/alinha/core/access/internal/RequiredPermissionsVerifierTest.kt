package pt.alinha.core.access.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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

    private fun handler(name: String) = HandlerMethod(Endpoints(), Endpoints::class.java.getDeclaredMethod(name))

    @Test
    fun `reports an endpoint whose permission no manifest declares`() {
        val undeclared = undeclaredPermissions(listOf(handler("declared"), handler("typo")), setOf("assessment:read"))

        assertThat(undeclared.values).containsExactly("assesment:read")
    }

    @Test
    fun `ignores declared and unannotated endpoints`() {
        val handlers = listOf(handler("declared"), handler("unannotated"))

        assertThat(undeclaredPermissions(handlers, setOf("assessment:read"))).isEmpty()
    }
}
