package pt.alinha.pluginapi

/**
 * Per-tenant credentials of provider plugins (e.g. a code repository token), stored in OpenBao (NFR09).
 * Secrets never go to the database, logs or audit payloads.
 *
 * `provider` is the provider plugin id (lowercase letters, digits and dashes).
 * The tenant is explicit for now; callers pass `TenantContext.current()` (never a value from the request).
 */
interface TenantSecretStore {
    fun put(tenant: TenantId, provider: String, secret: Map<String, String>)
    fun get(tenant: TenantId, provider: String): Map<String, String>?
    fun delete(tenant: TenantId, provider: String)
}
