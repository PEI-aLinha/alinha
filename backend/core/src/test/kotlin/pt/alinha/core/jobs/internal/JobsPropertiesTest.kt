package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class JobsPropertiesTest {

    @Test
    fun `a zero backoff base is refused`() {
        assertThatThrownBy { JobsProperties.Relay(backoffBase = Duration.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("backoff-base")
    }

    @Test
    fun `the defaults are valid`() {
        assertThatCode { JobsProperties() }.doesNotThrowAnyException()
    }
}
