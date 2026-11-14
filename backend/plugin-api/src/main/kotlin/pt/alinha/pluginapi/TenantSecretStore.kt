package pt.alinha.pluginapi

/**
 * Per-tenant credentials of provider plugins (e.g. a code repository token), stored in OpenBao (NFR09).
 * Secrets never go to the database, logs or audit payloads.
 *
 * `provider` is the provider plugin id (lowercase letters, digits and dashes).
 * The tenant is always the current one (the core's `TenantContext`), so a caller cannot reach
 * another tenant's credentials. Calls outside a tenant-scoped request or `runAs` fail.
 */
interface TenantSecretStore {
    fun put(provider: String, secret: Map<String, String>)
    fun get(provider: String): Map<String, String>?
    fun delete(provider: String)
}
