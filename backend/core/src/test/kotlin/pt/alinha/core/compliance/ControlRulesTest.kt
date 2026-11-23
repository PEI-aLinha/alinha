package pt.alinha.core.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pt.alinha.core.compliance.Answer.NO
import pt.alinha.core.compliance.Answer.PARTIAL
import pt.alinha.core.compliance.Answer.UNKNOWN
import pt.alinha.core.compliance.Answer.YES
import pt.alinha.core.compliance.ComplianceLevel.BASIC
import pt.alinha.core.compliance.ComplianceLevel.HIGH
import pt.alinha.core.compliance.ComplianceLevel.SUBSTANTIAL
import pt.alinha.core.compliance.ControlStatus.COMPLIANT
import pt.alinha.core.compliance.ControlStatus.GAP

private fun control(id: String, level: ComplianceLevel, questions: Int = 1) = Control(
    id = id, title = id, level = level, technical = false, evidence = emptyList(),
    questions = (1..questions).map { Question("$id-Q$it", "?", "", "", Answer.entries) },
)

/** Two basic controls (B1 with two questions), one substantial (S1) and one high (H1). */
private val CATALOGUE = Catalogue(
    framework = "test", version = 1, title = "Test", sources = emptyList(), requirements = emptyList(),
    controls = listOf(control("B1", BASIC, questions = 2), control("B2", BASIC), control("S1", SUBSTANTIAL), control("H1", HIGH)),
)

private val ALL_YES = mapOf("B1-Q1" to YES, "B1-Q2" to YES, "B2-Q1" to YES, "S1-Q1" to YES, "H1-Q1" to YES)

class ControlRulesTest {

    private val rules = ControlRules()

    @Test
    fun `a control is compliant only when every answer is yes`() {
        assertEquals(COMPLIANT, rules.status(listOf(YES, YES)))
        assertEquals(ControlStatus.PARTIAL, rules.status(listOf(YES, PARTIAL)))
        assertEquals(GAP, rules.status(listOf(PARTIAL, NO)))
        assertEquals(GAP, rules.status(listOf(YES, UNKNOWN)))
    }

    @Test
    fun `everything in place reaches the target level`() {
        val result = rules.evaluate(CATALOGUE, HIGH, ALL_YES)

        assertEquals(HIGH, result.currentLevel)
        assertEquals(HIGH, result.targetLevel)
        assertEquals(1.0, result.coverage)
        assertEquals(ControlRules.VERSION, result.engineVersion)
        assertEquals(listOf("B1", "B2", "S1", "H1"), result.controls.map { it.controlId })
    }

    @Test
    fun `the current level stops below the first level with a gap or a partial control`() {
        val result = rules.evaluate(CATALOGUE, HIGH, ALL_YES + ("S1-Q1" to PARTIAL))

        assertEquals(BASIC, result.currentLevel)
        assertEquals(0.75, result.coverage)
    }

    @Test
    fun `a gap in a basic control means no level is reached, even with the levels above in place`() {
        val result = rules.evaluate(CATALOGUE, HIGH, ALL_YES + ("B1-Q2" to UNKNOWN))

        assertNull(result.currentLevel)
        assertEquals(GAP, result.controls.first { it.controlId == "B1" }.status)
    }

    @Test
    fun `only the controls up to the assessment's level are evaluated`() {
        val answers = ALL_YES - "H1-Q1"

        val result = rules.evaluate(CATALOGUE, SUBSTANTIAL, answers)

        assertEquals(SUBSTANTIAL, result.currentLevel)
        assertEquals(listOf("B1", "B2", "S1"), result.controls.map { it.controlId })
    }

    @Test
    fun `refuses to evaluate with unanswered questions and says which`() {
        val error = assertThrows<IncompleteAnswersException> { rules.evaluate(CATALOGUE, BASIC, mapOf("B1-Q1" to YES)) }

        assertEquals(listOf("B1-Q2", "B2-Q1"), error.unanswered)
    }

    @Test
    fun `the same answers always give the same result`() {
        val answers = ALL_YES + ("B2-Q1" to NO)

        assertEquals(rules.evaluate(CATALOGUE, HIGH, answers), rules.evaluate(CATALOGUE, HIGH, answers))
    }
}
