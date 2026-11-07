package pt.alinha.core.secrets

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.vault.authentication.TokenAuthentication
import org.springframework.vault.client.VaultEndpoint
import org.springframework.vault.core.VaultTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.testcontainers.vault.VaultContainer
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

        private lateinit var store: OpenBaoTenantSecretStore

        @BeforeAll
        @JvmStatic
        fun setUp() {
            val endpoint = VaultEndpoint.from(URI.create(openBao.httpHostAddress))
            store = OpenBaoTenantSecretStore(VaultTemplate(endpoint, TokenAuthentication(ROOT_TOKEN)))
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
    fun `returns null after delete`() {
        val tenant = newTenant()
        store.put(tenant, "external-surface", mapOf("apiKey" to "k"))

        store.delete(tenant, "external-surface")

        assertThat(store.get(tenant, "external-surface")).isNull()
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
}
