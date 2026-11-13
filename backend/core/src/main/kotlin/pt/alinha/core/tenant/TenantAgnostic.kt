package pt.alinha.core.tenant

/** Endpoint that reads no tenant data (e.g. the plugin registry), so no tenant is resolved. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TenantAgnostic
