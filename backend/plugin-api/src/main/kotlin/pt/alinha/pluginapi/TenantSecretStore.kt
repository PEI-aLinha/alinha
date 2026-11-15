package pt.alinha.pluginapi

/**
 * Per-tenant credentials of provider plugins (e.g. a code repository token), stored in OpenBao (NFR09).
 * Secrets never go to the database, logs or audit payloads.
 *
 * The tenant is always the current one (the core's `TenantContext`), never a caller argument, so a
 * plugin cannot reach another tenant's credentials. Calls outside a tenant-scoped request or `runAs` fail.
 *
 * `provider` is the provider plugin id (lowercase letters, digits and dashes). It is not yet bound to
 * the calling plugin (#60): this is a trusted, in-process API, and a provider plugin must only pass its
 * own manifest id. Never take `provider` from a request.
 */
interface TenantSecretStore {
    fun put(provider: String, secret: Map<String, String>)
    fun get(provider: String): Map<String, String>?
    fun delete(provider: String)
}
