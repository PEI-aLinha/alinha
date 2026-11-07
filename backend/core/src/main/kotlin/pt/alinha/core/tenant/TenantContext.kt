package pt.alinha.core.tenant

import pt.alinha.pluginapi.TenantId

/** Tenant of the current request — ALWAYS taken from the token, never from body/query (RNF03). */
interface TenantContext {
    fun current(): TenantId
}
