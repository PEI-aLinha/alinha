package pt.alinha.core.access.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Endpoints whose `@RequiresPermission` names a permission no manifest declares, as handler -> permission.
 * Such an endpoint would answer 403 to every role, which looks exactly like a real refusal.
 */
internal fun undeclaredPermissions(handlers: Collection<HandlerMethod>, declared: Set<String>): Map<String, String> =
    handlers
        .mapNotNull { handler -> requiredPermission(handler)?.let { handler.shortLogMessage to it } }
        .filter { (_, permission) -> permission !in declared }
        .toMap()

/** Stops the application at startup when an endpoint requires an undeclared permission (e.g. a typo). */
@Component
class RequiredPermissionsVerifier(
    private val handlerMappings: List<RequestMappingHandlerMapping>,
    private val registry: RolePermissionRegistry,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val handlers = handlerMappings.flatMap { it.handlerMethods.values }
        val undeclared = undeclaredPermissions(handlers, registry.declaredPermissions)
        require(undeclared.isEmpty()) { "Endpoints require permissions no plugin declares: $undeclared" }
    }
}
