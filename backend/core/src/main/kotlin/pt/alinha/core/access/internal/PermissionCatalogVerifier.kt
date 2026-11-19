package pt.alinha.core.access.internal

import org.springframework.beans.factory.BeanFactoryUtils
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import pt.alinha.core.access.RequiresPermission

/**
 * Stops startup when an endpoint asks for a permission no role is granted: one no plugin declares and the core does
 * not own (almost always a typo in [RequiresPermission]), or one declared but left out of every `rolePermissions`.
 * Such an endpoint would answer 403 to everyone.
 */
@Component
class PermissionCatalogVerifier(private val registry: RolePermissionRegistry) {
    @EventListener
    fun onRefresh(event: ContextRefreshedEvent) {
        // Including ancestors, so a child context (e.g. management) still sees the app's mappings.
        val handlers = BeanFactoryUtils
            .beansOfTypeIncludingAncestors(event.applicationContext, RequestMappingHandlerMapping::class.java).values
            .flatMap { it.handlerMethods.values }
        verify(handlers)
    }

    fun verify(handlers: Collection<HandlerMethod>) {
        val unknown = handlers
            .mapNotNull { handler -> requiredPermission(handler)?.let { it to handler.shortLogMessage } }
            .filter { (permission, _) -> permission !in registry.grantedPermissions }
        check(unknown.isEmpty()) {
            "Endpoints require permissions no role is granted (undeclared, or missing from rolePermissions): " +
                unknown.joinToString { (permission, endpoint) -> "$permission on $endpoint" }
        }
    }
}
