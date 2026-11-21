package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class PublishBackoffTest {
    private val base = Duration.ofSeconds(2)
    private val max = Duration.ofMinutes(5)

    @Test
    fun `doubles per failure and caps at max`() {
        assertThat(publishBackoff(1, base, max)).isEqualTo(Duration.ofSeconds(2))
        assertThat(publishBackoff(2, base, max)).isEqualTo(Duration.ofSeconds(4))
        assertThat(publishBackoff(5, base, max)).isEqualTo(Duration.ofSeconds(32))
        assertThat(publishBackoff(9, base, max)).isEqualTo(max)
        assertThat(publishBackoff(1_000, base, max)).isEqualTo(max) // no overflow
    }
}
