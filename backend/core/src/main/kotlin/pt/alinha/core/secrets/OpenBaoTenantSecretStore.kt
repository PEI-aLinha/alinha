package pt.alinha.core.secrets

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.vault.core.VaultOperations
import org.springframework.vault.core.VaultVersionedKeyValueOperations
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantSecretStore

/**
 * Stores provider credentials in OpenBao KV v2 at `secret/tenants/{tenantId}/providers/{provider}`.
 * The tenant comes from [TenantContext], never from the caller.
 * The tenant id is a UUID and the provider id is validated, so a caller cannot escape its tenant path.
 *
 * Writes are audited (RNF04) in this order: audit entry, then OpenBao, then commit.
 * - OpenBao fails: the exception rolls back the transaction, so nothing changed and nothing is recorded.
 * - The commit fails after OpenBao succeeded: the change is not audited; it is logged as ERROR (docs/audit.md).
 * The audit entry never holds the secret value or its keys.
 *
 * Not created when `spring.cloud.vault.enabled=false` (tests without OpenBao): there is no
 * `VaultOperations` bean then, so a context test provides its own [TenantSecretStore] double.
 */
@Component
@ConditionalOnProperty(prefix = "spring.cloud.vault", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class OpenBaoTenantSecretStore(
    private val vault: VaultOperations,
    private val tenants: TenantContext,
    private val audit: AuditTrail,
) : TenantSecretStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val kv: VaultVersionedKeyValueOperations = vault.opsForVersionedKeyValue(KV_MOUNT)

    @Transactional
    override fun put(provider: String, secret: Map<String, String>) {
        require(secret.isNotEmpty()) { "secret must not be empty" }
        require(secret.all { (key, value) -> key.isNotBlank() && value.isNotBlank() }) {
            "secret keys and values must not be blank"
        }
        val path = path(provider)
        audit.record(ACTION_PUT, ENTITY_TYPE, provider, mapOf("provider" to provider))
        kv.put(path, secret)
        logIfNotCommitted(ACTION_PUT, provider)
    }

    override fun get(provider: String): Map<String, String>? =
        kv.get(path(provider))?.data?.mapValues { (key, value) ->
            check(value is String) { "secret field '$key' is not a string" }
            value
        }

    /**
     * Permanently removes the credential: deleting the KV v2 metadata destroys every version.
     * A plain `kv.delete` is only a soft delete of the latest version, so old tokens stay recoverable.
     */
    @Transactional
    override fun delete(provider: String) {
        val path = path(provider)
        audit.record(ACTION_DELETE, ENTITY_TYPE, provider, mapOf("provider" to provider))
        vault.delete("$KV_MOUNT/metadata/$path")
        logIfNotCommitted(ACTION_DELETE, provider)
    }

    private fun logIfNotCommitted(action: String, provider: String) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        val tenant = tenants.current().value
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    log.error(
                        "OpenBao {} succeeded but its audit entry was not committed: tenant={}, provider={}",
                        action, tenant, provider,
                    )
                }
            }
        })
    }

    private fun path(provider: String): String {
        require(PROVIDER_ID.matches(provider)) { "invalid provider id" }
        return "tenants/${tenants.current().value}/providers/$provider"
    }

    private companion object {
        const val KV_MOUNT = "secret"
        const val ENTITY_TYPE = "provider_credential"
        const val ACTION_PUT = "tenant_secret.put"
        const val ACTION_DELETE = "tenant_secret.delete"
        val PROVIDER_ID = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
    }
}
