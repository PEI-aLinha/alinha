package pt.alinha.core.access.internal

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Endpoints whose `@RequiresPermission` names a permission no role is granted, as handler -> permission: one no
 * manifest declares (a typo), or one declared but missing from every `rolePermissions`. Such an endpoint would answer
 * 403 to every role, which looks exactly like a real refusal.
 */
internal fun ungrantedPermissions(handlers: Collection<HandlerMethod>, granted: Set<String>): Map<String, String> =
    handlers
        .mapNotNull { handler -> requiredPermission(handler)?.let { handler.shortLogMessage to it } }
        .filter { (_, permission) -> permission !in granted }
        .toMap()

/**
 * Endpoints with no `@RequiresPermission` at all. The interceptor would deny them at request time; this finds
 * them at startup. Only Spring's own [BasicErrorController] is exempt, the same rule as the interceptor, which lets
 * error dispatches through: any other controller, an `ErrorController` included, needs a permission.
 */
internal fun unannotatedEndpoints(handlers: Collection<HandlerMethod>): List<String> =
    handlers
        .filterNot { BasicErrorController::class.java.isAssignableFrom(it.beanType) }
        .filter { requiredPermission(it) == null }
        .map { it.shortLogMessage }

/** Stops the application at startup when an endpoint has no permission or requires an undeclared one. */
@Component
class RequiredPermissionsVerifier(
    private val handlerMappings: List<RequestMappingHandlerMapping>,
    private val registry: RolePermissionRegistry,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val handlers = handlerMappings.flatMap { it.handlerMethods.values }
        val unannotated = unannotatedEndpoints(handlers)
        require(unannotated.isEmpty()) { "Endpoints without @RequiresPermission: $unannotated" }
        val ungranted = ungrantedPermissions(handlers, registry.grantedPermissions)
        require(ungranted.isEmpty()) {
            "Endpoints require permissions no role is granted (undeclared, or missing from rolePermissions): $ungranted"
        }
    }
}
