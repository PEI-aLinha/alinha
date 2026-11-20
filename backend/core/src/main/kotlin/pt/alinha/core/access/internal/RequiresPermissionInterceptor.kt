package pt.alinha.core.access.internal

import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import pt.alinha.core.access.RequiresPermission

/** The `resource:action` an endpoint requires; a method annotation replaces the class one. */
internal fun requiredPermission(handler: HandlerMethod): String? {
    val annotation = AnnotatedElementUtils.findMergedAnnotation(handler.method, RequiresPermission::class.java)
        ?: AnnotatedElementUtils.findMergedAnnotation(handler.beanType, RequiresPermission::class.java)
    return annotation?.let { "${it.resource}:${it.action}" }
}

/** RNF08: deny by default, so an endpoint that forgets [RequiresPermission] is closed rather than open. */
@Component
class RequiresPermissionInterceptor(private val registry: RolePermissionRegistry) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        // Spring's internal error dispatch renders the error of a request that was already checked (or refused).
        // A direct GET /error is a REQUEST dispatch and is checked like any other endpoint, so it is denied.
        if (request.dispatcherType == DispatcherType.ERROR) return true
        val required = (handler as? HandlerMethod)?.let(::requiredPermission)
            ?: throw AccessDeniedException("Endpoint does not declare a permission")
        if (required !in registry.permissionsFor(currentRoles())) {
            throw AccessDeniedException("Missing permission $required")
        }
        return true
    }

    private fun currentRoles(): Set<String> = rolesOf(SecurityContextHolder.getContext().authentication)
}

@Configuration
class AccessWebConfig(private val interceptor: RequiresPermissionInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor)
    }
}
