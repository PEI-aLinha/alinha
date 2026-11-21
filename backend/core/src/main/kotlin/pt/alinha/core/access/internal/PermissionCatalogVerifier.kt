package pt.alinha.core.access.internal

import org.springframework.beans.factory.BeanFactoryUtils
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import pt.alinha.core.access.RequiresPermission

/**
 * Stops startup when an endpoint is not protected the way the interceptor expects:
 * - it has no [RequiresPermission] at all. The interceptor would deny it at request time; this finds it at startup.
 *   Only Spring's own [BasicErrorController] is exempt, the same rule as the interceptor, which lets error
 *   dispatches through: any other controller, an `ErrorController` included, needs a permission.
 * - it asks for a permission no role is granted: one no plugin declares and the core does not own (almost always a
 *   typo in [RequiresPermission]), or one declared but left out of every `rolePermissions`. Such an endpoint would
 *   answer 403 to everyone, which looks exactly like a real refusal.
 *
 * Only annotated controllers ([RequestMappingHandlerMapping]) are checked here. Functional routes are not, but the
 * interceptor still denies them at request time, so they fail closed.
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
        val unannotated = handlers
            .filterNot { BasicErrorController::class.java.isAssignableFrom(it.beanType) }
            .filter { requiredPermission(it) == null }
        check(unannotated.isEmpty()) {
            "Endpoints without @RequiresPermission: " + unannotated.joinToString { it.shortLogMessage }
        }

        val ungranted = handlers
            .mapNotNull { handler -> requiredPermission(handler)?.let { it to handler.shortLogMessage } }
            .filter { (permission, _) -> permission !in registry.grantedPermissions }
        check(ungranted.isEmpty()) {
            "Endpoints require permissions no role is granted (undeclared, or missing from rolePermissions): " +
                ungranted.joinToString { (permission, endpoint) -> "$permission on $endpoint" }
        }
    }
}
