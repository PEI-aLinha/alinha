package pt.alinha.core.access.internal

import jakarta.servlet.DispatcherType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.method.HandlerMethod
import pt.alinha.core.registry.PluginRegistry

class RequiresPermissionInterceptorTest {
    @Suppress("unused")
    private class Endpoints {
        fun unannotated() = "ok"
    }

    @Test
    fun `denies an endpoint without RequiresPermission`() {
        // The startup verifier normally rejects such endpoints; the interceptor still denies as a backstop.
        val interceptor = RequiresPermissionInterceptor(RolePermissionRegistry(PluginRegistry(emptyList())))
        val handler = HandlerMethod(Endpoints(), Endpoints::class.java.getDeclaredMethod("unannotated"))

        assertThatThrownBy { interceptor.preHandle(MockHttpServletRequest(), MockHttpServletResponse(), handler) }
            .isInstanceOf(AccessDeniedException::class.java)
            .hasMessageContaining("does not declare a permission")
    }

    @Test
    fun `lets only error dispatches through, never a direct request to the error path`() {
        val interceptor = RequiresPermissionInterceptor(RolePermissionRegistry(PluginRegistry(emptyList())))
        val handler = HandlerMethod(Endpoints(), Endpoints::class.java.getDeclaredMethod("unannotated"))
        val errorDispatch = MockHttpServletRequest("GET", "/error").apply { dispatcherType = DispatcherType.ERROR }
        val direct = MockHttpServletRequest("GET", "/error")

        assertThat(interceptor.preHandle(errorDispatch, MockHttpServletResponse(), handler)).isTrue()
        assertThatThrownBy { interceptor.preHandle(direct, MockHttpServletResponse(), handler) }
            .isInstanceOf(AccessDeniedException::class.java)
    }
}
