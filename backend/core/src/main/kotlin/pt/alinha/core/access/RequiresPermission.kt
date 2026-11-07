package pt.alinha.core.access

/**
 * Marca um endpoint com o recurso/ação RBAC exigido (RNF09: role explícito
 * por operação). Verificado pelo Access Control do Core.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresPermission(val resource: String, val action: String = "read")
