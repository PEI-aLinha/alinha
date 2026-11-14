package pt.alinha.core.secrets

import org.springframework.stereotype.Component
import org.springframework.vault.core.VaultOperations
import org.springframework.vault.core.VaultVersionedKeyValueOperations
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantSecretStore

/**
 * Stores provider credentials in OpenBao KV v2 at `secret/tenants/{tenantId}/providers/{provider}`.
 * The tenant comes from [TenantContext], never from the caller.
 * The tenant id is a UUID and the provider id is validated, so a caller cannot escape its tenant path.
 */
@Component
class OpenBaoTenantSecretStore(
    private val vault: VaultOperations,
    private val tenants: TenantContext,
) : TenantSecretStore {

    private val kv: VaultVersionedKeyValueOperations = vault.opsForVersionedKeyValue(KV_MOUNT)

    // TODO(#10): record put/delete in AuditTrail (tenant, provider, action — never the secret value).
    override fun put(provider: String, secret: Map<String, String>) {
        require(secret.isNotEmpty()) { "secret must not be empty" }
        kv.put(path(provider), secret)
    }

    override fun get(provider: String): Map<String, String>? =
        kv.get(path(provider))?.data?.mapValues { (_, value) -> value.toString() }

    /**
     * Permanently removes the credential: deleting the KV v2 metadata destroys every version.
     * A plain `kv.delete` is only a soft delete of the latest version, so old tokens stay recoverable.
     */
    override fun delete(provider: String) {
        vault.delete("$KV_MOUNT/metadata/${path(provider)}")
    }

    private fun path(provider: String): String {
        require(PROVIDER_ID.matches(provider)) { "invalid provider id" }
        return "tenants/${tenants.current().value}/providers/$provider"
    }

    private companion object {
        const val KV_MOUNT = "secret"
        val PROVIDER_ID = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
    }
}
