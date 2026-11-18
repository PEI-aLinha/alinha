package pt.alinha.core.access.internal

import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import pt.alinha.core.access.RequiresPermission

/**
 * Stops startup when an endpoint asks for a permission no plugin declares and the core does not own.
 * Such an endpoint could never be called, which is almost always a typo in [RequiresPermission].
 */
@Component
class PermissionCatalogVerifier(private val registry: RolePermissionRegistry) {
    @EventListener
    fun onRefresh(event: ContextRefreshedEvent) {
        val handlers = event.applicationContext
            .getBeansOfType(RequestMappingHandlerMapping::class.java).values
            .flatMap { it.handlerMethods.values }
        verify(handlers)
    }

    fun verify(handlers: Collection<HandlerMethod>) {
        val unknown = handlers
            .mapNotNull { handler -> requiredPermission(handler)?.let { it to handler.shortLogMessage } }
            .filter { (permission, _) -> permission !in registry.knownPermissions }
        check(unknown.isEmpty()) {
            "Endpoints require permissions no plugin declares: " +
                unknown.joinToString { (permission, endpoint) -> "$permission on $endpoint" }
        }
    }
}
