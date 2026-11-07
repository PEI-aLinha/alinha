
package pt.alinha.core.tenant

import pt.alinha.pluginapi.TenantId

/** Tenant do pedido atual — vem SEMPRE do token, nunca do body/query (RNF04). */
interface TenantContext {
    fun current(): TenantId
}
