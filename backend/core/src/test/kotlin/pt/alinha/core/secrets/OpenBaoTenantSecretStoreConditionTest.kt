package pt.alinha.core.secrets

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.vault.core.VaultOperations
import org.springframework.vault.core.VaultVersionedKeyValueOperations
import pt.alinha.pluginapi.TenantSecretStore

class OpenBaoTenantSecretStoreConditionTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(OpenBaoTenantSecretStore::class.java)

    @Test
    fun `is not created when vault is disabled`() {
        runner.withPropertyValues("spring.cloud.vault.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(TenantSecretStore::class.java)
        }
    }

    @Test
    fun `is created when vault is enabled`() {
        val vault = mock(VaultOperations::class.java)
        `when`(vault.opsForVersionedKeyValue("secret"))
            .thenReturn(mock(VaultVersionedKeyValueOperations::class.java))

        runner.withBean(VaultOperations::class.java, { vault }).run { context ->
            assertThat(context).hasSingleBean(TenantSecretStore::class.java)
        }
    }
}
