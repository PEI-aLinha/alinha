package pt.alinha.core.secrets

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.vault.VaultException
import org.springframework.vault.authentication.TokenAuthentication
import org.springframework.vault.client.VaultEndpoint
import org.springframework.vault.core.VaultTemplate
import org.springframework.vault.support.VaultTokenRequest
import org.springframework.vault.support.Versioned
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.testcontainers.vault.VaultContainer
import pt.alinha.core.audit.AiProvenance
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.tenant.internal.JwtTenantContext
import pt.alinha.pluginapi.TenantId
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText

@Testcontainers
class OpenBaoTenantSecretStoreTest {

    companion object {
        private const val ROOT_TOKEN = "test-root-token"

        @Container
        @JvmStatic
        val openBao: VaultContainer<*> = VaultContainer(
            DockerImageName.parse("openbao/openbao:2.4.1").asCompatibleSubstituteFor("hashicorp/vault"),
        ).withVaultToken(ROOT_TOKEN)
            .withEnv("BAO_DEV_ROOT_TOKEN_ID", ROOT_TOKEN) // OpenBao ignores the VAULT_* name

        private const val APP_POLICY = "alinha-app"

        /** Root client, only for setup and assertions the app itself must not be able to make. */
        private lateinit var vault: VaultTemplate

        /** Client with the app's token: restricted to deploy/openbao/alinha-app.hcl, as in dev. */
        private lateinit var appVault: VaultTemplate
        private lateinit var store: OpenBaoTenantSecretStore
        private val tenants = JwtTenantContext()
        private val audit = RecordingAuditTrail()

        @BeforeAll
        @JvmStatic
        fun setUp() {
            val endpoint = VaultEndpoint.from(URI.create(openBao.httpHostAddress))
            vault = VaultTemplate(endpoint, TokenAuthentication(ROOT_TOKEN))

            val policyDir = Path.of(System.getProperty("alinha.openbao.policy-dir"))
            vault.write("sys/policies/acl/$APP_POLICY", mapOf("policy" to policyDir.resolve("$APP_POLICY.hcl").readText()))
            val appToken = vault.opsForToken().create(VaultTokenRequest.builder().withPolicy(APP_POLICY).build()).token

            appVault = VaultTemplate(endpoint, TokenAuthentication(appToken))
            store = OpenBaoTenantSecretStore(appVault, tenants, audit)
        }
    }

    private fun newTenant() = TenantId(UUID.randomUUID())

    private fun <T> asTenant(tenant: TenantId, block: () -> T): T = tenants.runAs(tenant, block)

    @Test
    fun `stores and reads a provider secret for a tenant`() {
        val tenant = newTenant()

        asTenant(tenant) { store.put("code-repository", mapOf("token" to "ghp_example")) }

        assertThat(asTenant(tenant) { store.get("code-repository") }).isEqualTo(mapOf("token" to "ghp_example"))
    }

    @Test
    fun `another tenant cannot read the secret`() {
        val owner = newTenant()
        asTenant(owner) { store.put("code-repository", mapOf("token" to "ghp_owner")) }

        assertThat(asTenant(newTenant()) { store.get("code-repository") }).isNull()
    }

    @Test
    fun `delete leaves another tenant's secret for the same provider untouched`() {
        val tenantA = newTenant()
        val tenantB = newTenant()
        asTenant(tenantA) { store.put("code-repository", mapOf("token" to "a")) }
        asTenant(tenantB) { store.put("code-repository", mapOf("token" to "b")) }

        asTenant(tenantA) { store.delete("code-repository") }

        assertThat(asTenant(tenantA) { store.get("code-repository") }).isNull()
        assertThat(asTenant(tenantB) { store.get("code-repository") }).isEqualTo(mapOf("token" to "b"))
    }

    @Test
    fun `put overwrites only the secret of its own tenant`() {
        val tenantA = newTenant()
        val tenantB = newTenant()
        asTenant(tenantA) { store.put("code-repository", mapOf("token" to "a1")) }
        asTenant(tenantB) { store.put("code-repository", mapOf("token" to "b1")) }

        asTenant(tenantA) { store.put("code-repository", mapOf("token" to "a2")) }

        assertThat(asTenant(tenantA) { store.get("code-repository") }).isEqualTo(mapOf("token" to "a2"))
        assertThat(asTenant(tenantB) { store.get("code-repository") }).isEqualTo(mapOf("token" to "b1"))
    }

    @Test
    fun `fails instead of coercing a stored value that is not a string`() {
        val tenant = newTenant()
        vault.opsForVersionedKeyValue("secret")
            .put("tenants/${tenant.value}/providers/code-repository", mapOf("port" to 8080))

        assertThatThrownBy { asTenant(tenant) { store.get("code-repository") } }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the app policy cannot list tenants or read secret metadata`() {
        val tenant = newTenant()
        asTenant(tenant) { store.put("code-repository", mapOf("token" to "t")) }

        assertThatThrownBy { appVault.list("secret/metadata/tenants") }
            .isInstanceOf(VaultException::class.java).hasMessageContaining("403")
        assertThatThrownBy { appVault.read("secret/metadata/tenants/${tenant.value}/providers/code-repository") }
            .isInstanceOf(VaultException::class.java).hasMessageContaining("403")
    }

    @Test
    fun `returns null after delete`() {
        val tenant = newTenant()
        asTenant(tenant) { store.put("external-surface", mapOf("apiKey" to "k")) }

        asTenant(tenant) { store.delete("external-surface") }

        assertThat(asTenant(tenant) { store.get("external-surface") }).isNull()
    }

    @Test
    fun `delete purges every version so old credentials cannot be recovered`() {
        val tenant = newTenant()
        asTenant(tenant) { store.put("code-repository", mapOf("token" to "old")) }
        asTenant(tenant) { store.put("code-repository", mapOf("token" to "new")) }

        asTenant(tenant) { store.delete("code-repository") }

        val kv = vault.opsForVersionedKeyValue("secret")
        val path = "tenants/${tenant.value}/providers/code-repository"
        assertThat(kv.get<Map<String, Any>>(path, Versioned.Version.from(1))?.data).isNull()
        assertThat(vault.read("secret/metadata/$path")).isNull()
    }

    @Test
    fun `the app policy cannot read the AI Service secrets`() {
        vault.opsForVersionedKeyValue("secret").put("alinha/ai-service", mapOf("llm.api-key" to "k"))

        assertThatThrownBy { appVault.opsForVersionedKeyValue("secret").get("alinha/ai-service") }
            .isInstanceOf(VaultException::class.java)
            .hasMessageContaining("403")
    }

    @Test
    fun `rejects provider ids that could escape the tenant path`() {
        listOf("../other", "a/b", "Code", "", "x".repeat(70)).forEach { bad ->
            assertThatThrownBy { asTenant(newTenant()) { store.get(bad) } }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rejects an empty secret`() {
        assertThatThrownBy { asTenant(newTenant()) { store.put("code-repository", emptyMap()) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects blank secret keys and values`() {
        listOf(mapOf("" to "v"), mapOf("k" to " ")).forEach { bad ->
            assertThatThrownBy { asTenant(newTenant()) { store.put("code-repository", bad) } }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `records put and delete without the secret value`() {
        val tenant = newTenant()
        asTenant(tenant) {
            store.put("code-repository", mapOf("token" to "ghp_secret"))
            store.delete("code-repository")
        }

        val entries = audit.entries.filter { it.entityId == "code-repository" }.takeLast(2)
        assertThat(entries.map { it.action }).containsExactly("tenant_secret.put", "tenant_secret.delete")
        assertThat(entries).allSatisfy {
            assertThat(it.entityType).isEqualTo("provider_credential")
            assertThat(it.details).isEqualTo(mapOf("provider" to "code-repository"))
            assertThat(it.toString()).doesNotContain("ghp_secret").doesNotContain("token")
        }
    }

    @Test
    fun `fails without a current tenant`() {
        assertThatThrownBy { store.get("code-repository") }.isInstanceOf(IllegalStateException::class.java)
    }
}

/** Records calls; the transactional behaviour is covered by AuditTrailIntegrationTest. */
private class RecordingAuditTrail : AuditTrail {
    data class Entry(val action: String, val entityType: String, val entityId: String, val details: Map<String, Any?>)

    val entries = mutableListOf<Entry>()

    override fun record(
        action: String,
        entityType: String,
        entityId: String,
        details: Map<String, Any?>,
        provenance: AiProvenance?,
    ) {
        entries += Entry(action, entityType, entityId, details)
    }
}
