package pt.alinha.core.secrets

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.vault.authentication.TokenAuthentication
import org.springframework.vault.client.VaultEndpoint
import org.springframework.vault.core.VaultTemplate
import org.springframework.vault.support.Versioned
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.testcontainers.vault.VaultContainer
import pt.alinha.core.tenant.internal.JwtTenantContext
import pt.alinha.pluginapi.TenantId
import java.net.URI
import java.util.UUID

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

        private lateinit var vault: VaultTemplate
        private val tenants = JwtTenantContext()
        private lateinit var store: OpenBaoTenantSecretStore

        @BeforeAll
        @JvmStatic
        fun setUp() {
            val endpoint = VaultEndpoint.from(URI.create(openBao.httpHostAddress))
            vault = VaultTemplate(endpoint, TokenAuthentication(ROOT_TOKEN))
            store = OpenBaoTenantSecretStore(vault, tenants)
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
    fun `returns null after delete`() {
        val tenant = newTenant()
        asTenant(tenant) {
            store.put("external-surface", mapOf("apiKey" to "k"))

            store.delete("external-surface")

            assertThat(store.get("external-surface")).isNull()
        }
    }

    @Test
    fun `delete purges every version so old credentials cannot be recovered`() {
        val tenant = newTenant()
        asTenant(tenant) {
            store.put("code-repository", mapOf("token" to "old"))
            store.put("code-repository", mapOf("token" to "new"))

            store.delete("code-repository")
        }

        val kv = vault.opsForVersionedKeyValue("secret")
        val path = "tenants/${tenant.value}/providers/code-repository"
        assertThat(kv.get<Map<String, Any>>(path, Versioned.Version.from(1))?.data).isNull()
        assertThat(vault.read("secret/metadata/$path")).isNull()
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
    fun `fails without a current tenant`() {
        assertThatThrownBy { store.get("code-repository") }.isInstanceOf(IllegalStateException::class.java)
    }
}
