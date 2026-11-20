package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataIntegrityViolationException
import pt.alinha.core.jobs.internal.JobResultConsumer.Companion.isDatabaseUnavailable
import java.sql.SQLException

class DatabaseUnavailableTest {
    @ParameterizedTest
    @ValueSource(strings = ["25006", "53100", "53200", "53300", "57P01", "57P03", "08006", "08001"])
    fun `states that mean the database cannot take writes now`(state: String) {
        assertThat(DataIntegrityViolationException("x", SQLException("x", state)).isDatabaseUnavailable()).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["22P05", "23505", "42P01", "57014", "XX000"])
    fun `states that are about this result or a bug`(state: String) {
        assertThat(DataIntegrityViolationException("x", SQLException("x", state)).isDatabaseUnavailable()).isFalse()
    }

    @Test
    fun `no SQLException at all is not an outage by itself`() {
        assertThat(IllegalStateException("bug").isDatabaseUnavailable()).isFalse()
    }
}
