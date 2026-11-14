package pt.alinha.core.access.internal

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
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import pt.alinha.core.access.RequiresPermission

/** RNF08: deny by default, so an endpoint that forgets [RequiresPermission] is closed rather than open. */
@Component
class RequiresPermissionInterceptor(private val registry: RolePermissionRegistry) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler is ResourceHttpRequestHandler) return true
        val required = (handler as? HandlerMethod)?.let(::requiredPermission)
            ?: throw AccessDeniedException("Endpoint does not declare a permission")
        if (required !in registry.permissionsFor(currentRoles())) {
            throw AccessDeniedException("Missing permission $required")
        }
        return true
    }

    private fun requiredPermission(handler: HandlerMethod): String? {
        val annotation = AnnotatedElementUtils.findMergedAnnotation(handler.method, RequiresPermission::class.java)
            ?: AnnotatedElementUtils.findMergedAnnotation(handler.beanType, RequiresPermission::class.java)
        return annotation?.let { "${it.resource}:${it.action}" }
    }

    private fun currentRoles(): Set<String> =
        SecurityContextHolder.getContext().authentication?.authorities.orEmpty()
            .mapNotNull { it.authority }
            .filter { it.startsWith(ROLE_PREFIX) }
            .map { it.removePrefix(ROLE_PREFIX) }
            .toSet()
}

@Configuration
class AccessWebConfig(private val interceptor: RequiresPermissionInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).excludePathPatterns("/error")
    }
}
