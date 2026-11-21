package pt.alinha

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DemoProfileGuardTest {
    private fun runner(vararg profiles: String) = ApplicationContextRunner()
        .withInitializer { it.environment.setActiveProfiles(*profiles) }
        .withUserConfiguration(DemoProfileGuard::class.java)

    @Test
    fun `starts with demo alone`() {
        runner("demo").run { assertThat(it).hasNotFailed().hasSingleBean(DemoProfileGuard::class.java) }
    }

    @Test
    fun `refuses demo together with prod`() {
        runner("prod", "demo").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining(DemoProfileGuard.ALLOW_IN_PROD)
        }
    }

    @Test
    fun `starts with demo and prod on the demo server`() {
        runner("prod", "demo").withPropertyValues("${DemoProfileGuard.ALLOW_IN_PROD}=true")
            .run { assertThat(it).hasNotFailed() }
    }

    @Test
    fun `is not created without demo`() {
        runner("prod").run { assertThat(it).hasNotFailed().doesNotHaveBean(DemoProfileGuard::class.java) }
    }
}
