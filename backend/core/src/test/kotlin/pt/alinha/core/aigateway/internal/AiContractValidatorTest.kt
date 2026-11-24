package pt.alinha.core.aigateway.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pt.alinha.core.contracts.ContractExamples
import tools.jackson.databind.json.JsonMapper

/** The core's side of contracts/ai, checked against the examples the AI Service tests also use. */
class AiContractValidatorTest {
    private val json = JsonMapper.builder().build()
    private val validator = AiContractValidator(json)
    private val examples = ContractExamples.of("ai")

    /** contracts/ai/examples/ai-service.v1/<Schema>/... → <Schema> */
    private val String.schemaName get() = substringAfterLast('/')

    @TestFactory
    fun `every AI example is accepted or refused as its folder says`() = examples.map { example ->
        DynamicTest.dynamicTest(example.name) {
            val body = example.document(json)
            if (example.valid) {
                assertDoesNotThrow { validator.validate(example.contract.schemaName, body) }
            } else {
                assertThrows<IllegalArgumentException> { validator.validate(example.contract.schemaName, body) }
            }
        }
    }

    @Test
    fun `the request and the draft both have valid and invalid examples`() {
        val bySchema = examples.groupBy { it.contract.schemaName }
        assertThat(bySchema.keys).contains("DraftRequest", "AiDraft")
        bySchema.forEach { (schema, cases) -> assertThat(cases.map { it.valid }).describedAs(schema).contains(true, false) }
    }

    @Test
    fun `error message names the field but never echoes the body`() {
        val secret = "s3cr3t-draft-text"
        val draft = examples.first { it.valid && it.contract.schemaName == "AiDraft" }.document(json)

        val e = assertThrows<IllegalArgumentException> {
            validator.validate("AiDraft", draft - "sources" + ("generatedAt" to secret) + ("model" to listOf(secret)))
        }

        assertThat(e.message).contains("required sources", "/generatedAt: format", "/model: type").doesNotContain(secret)
    }

    @Test
    fun `an unknown schema name is refused`() {
        val e = assertThrows<IllegalArgumentException> { validator.validate("NoSuchSchema", emptyMap()) }
        assertThat(e.message).contains("No schema NoSuchSchema")
    }
}
