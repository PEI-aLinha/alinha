package pt.alinha.core.access

/**
 * Marks an endpoint with the required RBAC resource/action (RNF08: explicit
 * roles per operation; RNF07: only login and health check skip the token).
 * Enforced by the core's Access Control.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresPermission(val resource: String, val action: String = "read")
