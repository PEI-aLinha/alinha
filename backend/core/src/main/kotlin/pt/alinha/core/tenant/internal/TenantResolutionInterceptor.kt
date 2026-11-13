package pt.alinha.core.tenant.internal

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import pt.alinha.core.tenant.TenantAgnostic

internal const val TENANT_ATTRIBUTE = "pt.alinha.core.tenant.current"

/** Runs after the RBAC interceptor, so a missing permission answers 403 before any tenant check. */
private const val TENANT_INTERCEPTOR_ORDER = 1

/** RNF03: every endpoint resolves its tenant up front unless it is [TenantAgnostic]. */
@Component
class TenantResolutionInterceptor : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod || isTenantAgnostic(handler)) return true
        val tenant = resolveTenant(SecurityContextHolder.getContext().authentication, request.getHeader(TENANT_HEADER))
        request.setAttribute(TENANT_ATTRIBUTE, tenant)
        return true
    }

    private fun isTenantAgnostic(handler: HandlerMethod): Boolean =
        handler.hasMethodAnnotation(TenantAgnostic::class.java) ||
                AnnotatedElementUtils.hasAnnotation(handler.beanType, TenantAgnostic::class.java)
}

@Configuration
class TenantWebConfig(private val interceptor: TenantResolutionInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).excludePathPatterns("/error").order(TENANT_INTERCEPTOR_ORDER)
    }
}