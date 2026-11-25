package pt.alinha.core.compliance

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

/**
 * Golden tests: a real catalogue and a fixed set of answers must give exactly the stored result (RNF05).
 * On a mismatch the actual result is written to build/golden-actual-<name>.json: review it and, when the change is
 * intended, copy it over the stored result (and bump ComplianceRules.VERSION once main has the old one).
 */
class ComplianceRulesGoldenTest {

    private data class Fixture(
        val description: String,
        val targetLevel: ComplianceLevel,
        val answers: Map<String, Answer>,
    )

    private val mapper =
        JsonMapper.builder().addModule(kotlinModule()).enable(SerializationFeature.INDENT_OUTPUT).build()

    // Tests run from backend/core, so the repo's frameworks folder is two levels up.
    private val catalogues = CatalogueLoader(Path.of("../../frameworks")).loadAll()

    @ParameterizedTest
    @CsvSource("nis2-qnrcs, 1, nis2-qnrcs-v1-rui")
    fun `evaluation matches the stored result`(framework: String, version: Int, name: String) {
        val catalogue = catalogues.single { it.framework == framework && it.version == version }
        val fixture: Fixture = mapper.readValue(resource("$name.answers.json"))

        val actual = mapper.writeValueAsString(
            ComplianceRules().evaluate(EvaluationInput(catalogue, fixture.targetLevel, fixture.answers)),
        )

        val expected = javaClass.getResource("/compliance/golden/$name.result.json")?.readText()
        if (expected == null || mapper.readTree(expected) != mapper.readTree(actual)) {
            val file = Path.of("build/golden-actual-$name.json")
            file.parent.createDirectories()
            file.writeText(actual + "\n")
            fail("Result differs from $name.result.json; actual written to ${file.toAbsolutePath()}")
        }
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResource("/compliance/golden/$path")) { "missing $path" }.readText()
}
