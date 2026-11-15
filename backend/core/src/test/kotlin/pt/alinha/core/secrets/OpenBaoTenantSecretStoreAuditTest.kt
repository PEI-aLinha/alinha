package pt.alinha.core.secrets

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.vault.VaultException
import org.springframework.vault.core.VaultOperations
import org.springframework.vault.core.VaultVersionedKeyValueOperations
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.audit.internal.AuditDatabase
import pt.alinha.core.audit.internal.AuditTestApplication
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.TenantId
import pt.alinha.pluginapi.TenantSecretStore
import java.util.UUID

private const val FAILING_PROVIDER = "audit-fails"

/**
 * The store as the app wires it: its @Transactional proxy plus the real JpaAuditTrail
 * (Propagation.MANDATORY) on PostgreSQL. Only OpenBao is a mock, to control when it fails.
 */
@SpringBootTest(classes = [AuditTestApplication::class, OpenBaoTenantSecretStoreAuditTest.StoreConfig::class])
class OpenBaoTenantSecretStoreAuditTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) = AuditDatabase.register(registry)
    }

    @TestConfiguration
    class StoreConfig {
        @Bean
        fun kv(): VaultVersionedKeyValueOperations = mock(VaultVersionedKeyValueOperations::class.java)

        @Bean
        fun vault(kv: VaultVersionedKeyValueOperations): VaultOperations =
            mock(VaultOperations::class.java).also { `when`(it.opsForVersionedKeyValue("secret")).thenReturn(kv) }

        @Bean
        fun store(vault: VaultOperations, tenants: TenantContext, audit: AuditTrail): TenantSecretStore =
            OpenBaoTenantSecretStore(vault, tenants, audit)
    }

    @Autowired private lateinit var store: TenantSecretStore
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var kv: VaultVersionedKeyValueOperations
    @Autowired private lateinit var vault: VaultOperations
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val tenant = TenantId(UUID.randomUUID())

    @BeforeEach
    fun resetOpenBao() = reset(kv)

    /** Makes the audit INSERT fail for one provider id, to check what happens before OpenBao is called. */
    @BeforeEach
    fun failingAuditInsert() {
        jdbc.execute(
            """
            create or replace function public.fail_audit_insert() returns trigger language plpgsql as
            ${'$'}${'$'} begin raise exception 'audit insert failed (test)'; end ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_audit_insert before insert on audit.audit_event for each row " +
                "when (new.entity_id = '$FAILING_PROVIDER') execute function public.fail_audit_insert()",
        )
    }

    @AfterEach
    fun dropFailingAuditInsert() {
        jdbc.execute("drop trigger if exists fail_audit_insert on audit.audit_event")
        jdbc.execute("drop function if exists public.fail_audit_insert()")
    }

    private fun auditActions(provider: String): List<String> = jdbc.queryForList(
        "select action from audit.audit_event where tenant_id = ? and entity_type = 'provider_credential' " +
            "and entity_id = ? order by occurred_at, id",
        String::class.java, tenant.value, provider,
    )

    @Test
    fun `put and delete commit their audit entries through the transactional proxy`() {
        tenants.runAs(tenant) {
            store.put("code-repository", mapOf("token" to "ghp_secret"))
            store.delete("code-repository")
        }

        assertThat(auditActions("code-repository")).containsExactly("tenant_secret.put", "tenant_secret.delete")
        verify(kv).put("tenants/${tenant.value}/providers/code-repository", mapOf("token" to "ghp_secret"))
        verify(vault).delete("secret/metadata/tenants/${tenant.value}/providers/code-repository")
    }

    @Test
    fun `a failing audit insert stops put before OpenBao is touched`() {
        assertThrows<Exception> {
            tenants.runAs(tenant) { store.put(FAILING_PROVIDER, mapOf("token" to "t")) }
        }

        verify(kv, never()).put(anyString(), any())
    }

    @Test
    fun `an OpenBao failure rolls back the audit entry`() {
        doThrow(VaultException("OpenBao down")).`when`(kv).put(anyString(), any())

        assertThrows<VaultException> {
            tenants.runAs(tenant) { store.put("external-surface", mapOf("apiKey" to "k")) }
        }

        assertThat(auditActions("external-surface")).isEmpty()
    }
}
