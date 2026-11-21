package pt.alinha

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DemoProfileGuardTest {
    private fun runner(vararg profiles: String) = ApplicationContextRunner()
        .withInitializer { it.environment.setActiveProfiles(*profiles) }
        .withUserConfiguration(DemoProfileGuard::class.java)

    @Test
    fun `starts with demo alone (local development)`() {
        runner("demo").run { assertThat(it).hasNotFailed().hasSingleBean(DemoProfileGuard::class.java) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["prod", "staging", "production", "anything"])
    fun `refuses demo together with any other profile`(other: String) {
        runner(other, "demo").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .hasMessageContaining(DemoProfileGuard.ALLOW).hasMessageContaining("'$other'")
        }
    }

    @Test
    fun `starts with demo in a deployment that says it is the demo server`() {
        runner("prod", "demo").withPropertyValues("${DemoProfileGuard.ALLOW}=true")
            .run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `is not created without demo`() {
        runner("prod").run { assertThat(it).hasNotFailed().doesNotHaveBean(DemoProfileGuard::class.java) }
    }
}
