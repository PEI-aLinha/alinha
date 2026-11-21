package pt.alinha.core.jobs.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pt.alinha.core.jobs.JobRequest

class JobRequestTest {
    @Test
    fun `rejects a payload that sets a field the core stamps`() {
        JobRequest.STAMPED_FIELDS.forEach { field ->
            assertThrows<IllegalArgumentException> { collectRequest(payload = validCollectPayload() + (field to "x")) }
        }
    }

    @Test
    fun `rejects malformed type, version, plugin id and key`() {
        assertThrows<IllegalArgumentException> { JobRequest("Evidence Collect", emptyMap(), "code-repository") }
        assertThrows<IllegalArgumentException> { JobRequest("evidence", emptyMap(), "code-repository") }
        assertThrows<IllegalArgumentException> { JobRequest("evidence.collect", emptyMap(), "code-repository", version = 0) }
        assertThrows<IllegalArgumentException> { JobRequest("evidence.collect", emptyMap(), "Code Repository") }
        assertThrows<IllegalArgumentException> { collectRequest(key = " ") }
        assertThrows<IllegalArgumentException> { collectRequest(key = "k".repeat(201)) }
    }
}
