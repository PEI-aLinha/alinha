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

        @BeforeAll
        @JvmStatic
        fun setUp() {
            val endpoint = VaultEndpoint.from(URI.create(openBao.httpHostAddress))
            vault = VaultTemplate(endpoint, TokenAuthentication(ROOT_TOKEN))

            val policyDir = Path.of(System.getProperty("alinha.openbao.policy-dir"))
            vault.write("sys/policies/acl/$APP_POLICY", mapOf("policy" to policyDir.resolve("$APP_POLICY.hcl").readText()))
            val appToken = vault.opsForToken().create(VaultTokenRequest.builder().withPolicy(APP_POLICY).build()).token

            appVault = VaultTemplate(endpoint, TokenAuthentication(appToken))
            store = OpenBaoTenantSecretStore(appVault)
        }
    }

    private fun newTenant() = TenantId(UUID.randomUUID())

    @Test
    fun `stores and reads a provider secret for a tenant`() {
        val tenant = newTenant()

        store.put(tenant, "code-repository", mapOf("token" to "ghp_example"))

        assertThat(store.get(tenant, "code-repository")).isEqualTo(mapOf("token" to "ghp_example"))
    }

    @Test
    fun `another tenant cannot read the secret`() {
        val owner = newTenant()
        store.put(owner, "code-repository", mapOf("token" to "ghp_owner"))

        assertThat(store.get(newTenant(), "code-repository")).isNull()
    }

    @Test
    fun `delete leaves another tenant's secret for the same provider untouched`() {
        val tenantA = newTenant()
        val tenantB = newTenant()
        store.put(tenantA, "code-repository", mapOf("token" to "a"))
        store.put(tenantB, "code-repository", mapOf("token" to "b"))

        store.delete(tenantA, "code-repository")

        assertThat(store.get(tenantA, "code-repository")).isNull()
        assertThat(store.get(tenantB, "code-repository")).isEqualTo(mapOf("token" to "b"))
    }

    @Test
    fun `put overwrites only the secret of its own tenant`() {
        val tenantA = newTenant()
        val tenantB = newTenant()
        store.put(tenantA, "code-repository", mapOf("token" to "a1"))
        store.put(tenantB, "code-repository", mapOf("token" to "b1"))

        store.put(tenantA, "code-repository", mapOf("token" to "a2"))

        assertThat(store.get(tenantA, "code-repository")).isEqualTo(mapOf("token" to "a2"))
        assertThat(store.get(tenantB, "code-repository")).isEqualTo(mapOf("token" to "b1"))
    }

    @Test
    fun `fails instead of coercing a stored value that is not a string`() {
        val tenant = newTenant()
        vault.opsForVersionedKeyValue("secret")
            .put("tenants/${tenant.value}/providers/code-repository", mapOf("port" to 8080))

        assertThatThrownBy { store.get(tenant, "code-repository") }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `the app policy cannot list tenants or read secret metadata`() {
        val tenant = newTenant()
        store.put(tenant, "code-repository", mapOf("token" to "t"))

        assertThatThrownBy { appVault.list("secret/metadata/tenants") }
            .isInstanceOf(VaultException::class.java).hasMessageContaining("403")
        assertThatThrownBy { appVault.read("secret/metadata/tenants/${tenant.value}/providers/code-repository") }
            .isInstanceOf(VaultException::class.java).hasMessageContaining("403")
    }

    @Test
    fun `returns null after delete`() {
        val tenant = newTenant()
        store.put(tenant, "external-surface", mapOf("apiKey" to "k"))

        store.delete(tenant, "external-surface")

        assertThat(store.get(tenant, "external-surface")).isNull()
    }

    @Test
    fun `delete purges every version so old credentials cannot be recovered`() {
        val tenant = newTenant()
        store.put(tenant, "code-repository", mapOf("token" to "old"))
        store.put(tenant, "code-repository", mapOf("token" to "new"))

        store.delete(tenant, "code-repository")

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
            assertThatThrownBy { store.get(newTenant(), bad) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `rejects an empty secret`() {
        assertThatThrownBy { store.put(newTenant(), "code-repository", emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects blank secret keys and values`() {
        listOf(mapOf("" to "v"), mapOf("k" to " ")).forEach { bad ->
            assertThatThrownBy { store.put(newTenant(), "code-repository", bad) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
