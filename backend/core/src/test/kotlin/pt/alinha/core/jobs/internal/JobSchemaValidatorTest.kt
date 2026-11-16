package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

class JobSchemaValidatorTest {
    private val validator = JobSchemaValidator(JsonMapper.builder().build())

    private fun envelope() = validCollectPayload() + mapOf(
        "jobId" to UUID.randomUUID().toString(),
        "type" to "evidence.collect",
        "tenantId" to UUID.randomUUID().toString(),
        "requestedAt" to "2026-11-16T10:00:00Z",
    )

    @Test
    fun `maps type and version to the contract file`() {
        assertThat(JobSchemaValidator.envelopeSchema("evidence.collect", 1)).isEqualTo("evidence-collect.v1")
    }

    @Test
    fun `accepts a valid envelope`() {
        assertDoesNotThrow { validator.validate("evidence-collect.v1", envelope()) }
    }

    @Test
    fun `rejects missing fields, unknown fields and bad formats`() {
        assertThrows<IllegalArgumentException> { validator.validate("evidence-collect.v1", envelope() - "provider") }
        assertThrows<IllegalArgumentException> { validator.validate("evidence-collect.v1", envelope() + ("token" to "x")) }
        assertThrows<IllegalArgumentException> { validator.validate("evidence-collect.v1", envelope() + ("jobId" to "not-a-uuid")) }
        assertThrows<IllegalArgumentException> { validator.validate("evidence-collect.v1", envelope() + ("requestedAt" to "yesterday")) }
    }

    @Test
    fun `error message never echoes values`() {
        val e = assertThrows<IllegalArgumentException> {
            validator.validate("evidence-collect.v1", envelope() + ("connectionRef" to 424242))
        }
        assertThat(e.message).contains("connectionRef").doesNotContain("424242")
    }

    @Test
    fun `error message never echoes bad formats, enum values or unknown property names`() {
        val secret = "s3cr3t-value"
        val bad = envelope() + mapOf(
            "jobId" to secret, // format: uuid
            "requestedAt" to "$secret-date", // format: date-time
            "provider" to "$secret-provider", // pattern / enum
            "$secret-key" to "x", // additionalProperties
        )

        val e = assertThrows<IllegalArgumentException> { validator.validate("evidence-collect.v1", bad) }

        assertThat(e.message).contains("/jobId: format", "/requestedAt: format", "additionalProperties").doesNotContain(secret)
    }

    @Test
    fun `error message names a missing required property`() {
        val e = assertThrows<IllegalArgumentException> { validator.validate("evidence-collect.v1", envelope() - "provider") }
        assertThat(e.message).contains("required provider")
    }

    @Test
    fun `rejects a type without a contract`() {
        val e = assertThrows<IllegalArgumentException> { validator.validate("nothing-here.v1", emptyMap()) }
        assertThat(e.message).contains("No job contract")
    }
}
