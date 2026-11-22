package pt.alinha

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DemoProfileGuardTest {
    private val localDb = "${DemoProfileGuard.DATASOURCE_URL}=jdbc:postgresql://localhost:5432/alinha"
    private val remoteDb = "${DemoProfileGuard.DATASOURCE_URL}=jdbc:postgresql://postgres:5432/alinha"

    private fun runner(vararg profiles: String) = ApplicationContextRunner()
        .withInitializer { it.environment.setActiveProfiles(*profiles) }
        .withUserConfiguration(DemoProfileGuard::class.java)

    @Test
    fun `starts with demo alone on a local database (local development)`() {
        runner("demo").withPropertyValues(localDb)
            .run { assertThat(it).hasNotFailed().hasSingleBean(DemoProfileGuard::class.java) }
    }

    @Test
    fun `refuses demo alone on a remote database`() {
        runner("demo").withPropertyValues(remoteDb).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("not on localhost")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["prod", "staging", "production", "anything"])
    fun `refuses demo together with any other profile, even on a local database`(other: String) {
        runner(other, "demo").withPropertyValues(localDb).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining(DemoProfileGuard.ALLOW).hasMessageContaining("'$other'")
        }
    }

    @Test
    fun `starts anywhere when the deployment says it is the demo server`() {
        runner("prod", "demo").withPropertyValues(remoteDb, "${DemoProfileGuard.ALLOW}=true")
            .run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `is not created without demo`() {
        runner("prod").withPropertyValues(remoteDb)
            .run { assertThat(it).hasNotFailed().doesNotHaveBean(DemoProfileGuard::class.java) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jdbc:postgresql://localhost:5432/a", "jdbc:postgresql://127.0.0.1/a", "jdbc:postgresql://[::1]:5432/a"])
    fun `recognises a local database`(url: String) {
        assertThat(DemoProfileGuard.isLocalDatabase(url)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["jdbc:postgresql://postgres:5432/a", "jdbc:postgresql://db.example.pt/a", "", "not-a-url"])
    fun `treats anything else as remote`(url: String) {
        assertThat(DemoProfileGuard.isLocalDatabase(url)).isFalse()
    }
}
