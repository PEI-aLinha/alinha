package pt.alinha.core.secrets

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.vault.core.VaultOperations
import org.springframework.vault.core.VaultVersionedKeyValueOperations
import pt.alinha.pluginapi.TenantId
import pt.alinha.pluginapi.TenantSecretStore

/**
 * Stores provider credentials in OpenBao KV v2 at `secret/tenants/{tenantId}/providers/{provider}`.
 * The tenant id is a UUID and the provider id is validated, so a caller cannot escape its tenant path.
 *
 * Not created when `spring.cloud.vault.enabled=false` (tests without OpenBao): there is no
 * `VaultOperations` bean then, so a context test provides its own [TenantSecretStore] double.
 */
@Component
@ConditionalOnProperty(prefix = "spring.cloud.vault", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OpenBaoTenantSecretStore(private val vault: VaultOperations) : TenantSecretStore {

    private val kv: VaultVersionedKeyValueOperations = vault.opsForVersionedKeyValue(KV_MOUNT)

    // TODO(#10): record put/delete in AuditTrail (tenant, provider, action — never the secret value).
    override fun put(tenant: TenantId, provider: String, secret: Map<String, String>) {
        require(secret.isNotEmpty()) { "secret must not be empty" }
        kv.put(path(tenant, provider), secret)
    }

    override fun get(tenant: TenantId, provider: String): Map<String, String>? =
        kv.get(path(tenant, provider))?.data?.mapValues { (_, value) -> value.toString() }

    /**
     * Permanently removes the credential: deleting the KV v2 metadata destroys every version.
     * A plain `kv.delete` is only a soft delete of the latest version, so old tokens stay recoverable.
     */
    override fun delete(tenant: TenantId, provider: String) {
        vault.delete("$KV_MOUNT/metadata/${path(tenant, provider)}")
    }

    private fun path(tenant: TenantId, provider: String): String {
        require(PROVIDER_ID.matches(provider)) { "invalid provider id" }
        return "tenants/${tenant.value}/providers/$provider"
    }

    private companion object {
        const val KV_MOUNT = "secret"
        val PROVIDER_ID = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
    }
}
