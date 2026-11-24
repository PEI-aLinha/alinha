package pt.alinha.core.jobs.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pt.alinha.core.contracts.ContractExamples
import tools.jackson.databind.json.JsonMapper
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** The core's side of contracts/jobs, checked against the examples the worker tests also use. */
class JobContractExamplesTest {
    private val json = JsonMapper.builder().build()
    private val validator = JobSchemaValidator(json)
    private val examples = ContractExamples.of("jobs")

    @TestFactory
    fun `every job example is accepted or refused as its folder says`() = examples.map { example ->
        DynamicTest.dynamicTest(example.name) {
            val document = example.document(json)
            if (example.valid) {
                assertDoesNotThrow { validator.validate(example.contract, document) }
            } else {
                assertThrows<IllegalArgumentException> { validator.validate(example.contract, document) }
            }
        }
    }

    @TestFactory
    fun `every valid envelope example passes the dispatcher's own check`() =
        examples.filter { it.valid && it.contract != RESULT_CONTRACT }.map { example ->
            DynamicTest.dynamicTest(example.name) {
                val document = example.document(json)
                val version = example.contract.substringAfterLast(".v").toInt()
                assertDoesNotThrow { validator.validateEnvelope(document["type"] as String, version, document) }
            }
        }

    @Test
    fun `every job contract has valid and invalid examples`() {
        val schemas = ContractExamples.root.resolve("jobs").listDirectoryEntries("*.schema.json").map { it.name.removeSuffix(".schema.json") }
        val contracts = examples.groupBy { it.contract }
        assertThat(contracts.keys).containsExactlyInAnyOrderElementsOf(schemas)
        contracts.forEach { (contract, cases) ->
            assertThat(cases.map { it.valid }).describedAs(contract).contains(true, false)
        }
    }

    private companion object {
        const val RESULT_CONTRACT = "job-result.v1"
    }
}
