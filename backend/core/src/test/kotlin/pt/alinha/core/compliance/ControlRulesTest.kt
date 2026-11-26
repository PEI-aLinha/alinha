package pt.alinha.core.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pt.alinha.core.compliance.Answer.NO
import pt.alinha.core.compliance.Answer.PARTIAL
import pt.alinha.core.compliance.Answer.UNKNOWN
import pt.alinha.core.compliance.Answer.YES
import pt.alinha.core.compliance.ControlStatus.COMPLIANT
import pt.alinha.core.compliance.ControlStatus.GAP
import pt.alinha.core.compliance.TestCatalogues.BASIC
import pt.alinha.core.compliance.TestCatalogues.HIGH
import pt.alinha.core.compliance.TestCatalogues.SUBSTANTIAL

private fun control(id: String, level: String, questions: Int = 1) = Control(
    id = id, title = id, level = level, technical = false, evidence = emptyList(),
    questions = (1..questions).map { Question("$id-Q$it", "?", "", "", Answer.entries) },
)

/** Two basic controls (B1 with two questions), one substantial (S1) and one high (H1); no evidence bars. */
private val CATALOGUE = Catalogue(
    framework = "test", version = 1, title = "Test", sources = emptyList(), requirements = emptyList(),
    levels = TestCatalogues.PLAIN_LEVELS,
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
    fun `answers outside the evaluated controls are ignored`() {
        val extra = ALL_YES + ("H1-Q1" to NO) + ("NOT-A-QUESTION" to NO)

        val result = rules.evaluate(CATALOGUE, SUBSTANTIAL, extra)

        assertEquals(rules.evaluate(CATALOGUE, SUBSTANTIAL, ALL_YES - "H1-Q1"), result)
        assertEquals(SUBSTANTIAL, result.currentLevel)
    }

    @Test
    fun `refuses to evaluate with unanswered questions and says which`() {
        val error = assertThrows<IncompleteAnswersException> { rules.evaluate(CATALOGUE, BASIC, mapOf("B1-Q1" to YES)) }

        assertEquals(listOf("B1-Q2", "B2-Q1"), error.unanswered)
    }

    @Test
    fun `a level without controls is never reached on its own and does not block the levels above`() {
        val noSubstantial = CATALOGUE.copy(controls = CATALOGUE.controls.filterNot { it.level == SUBSTANTIAL })
        val answers = ALL_YES - "S1-Q1"

        assertEquals(BASIC, rules.evaluate(noSubstantial, SUBSTANTIAL, answers).currentLevel)
        assertEquals(HIGH, rules.evaluate(noSubstantial, HIGH, answers).currentLevel)
    }

    @Test
    fun `no controls in scope is an error, never full compliance`() {
        val onlyHigh = CATALOGUE.copy(controls = CATALOGUE.controls.filter { it.level == HIGH })

        assertThrows<IllegalArgumentException> { rules.evaluate(onlyHigh, BASIC, emptyMap()) }
    }

    @Test
    fun `a control without answers is an error, never compliant`() {
        assertThrows<IllegalArgumentException> { rules.status(emptyList()) }
    }

    @Test
    fun `the same answers always give the same result`() {
        val answers = ALL_YES + ("B2-Q1" to NO)

        assertEquals(rules.evaluate(CATALOGUE, HIGH, answers), rules.evaluate(CATALOGUE, HIGH, answers))
    }

    @Test
    fun `the result carries the framework and the catalogue version`() {
        val result = rules.evaluate(CATALOGUE, HIGH, ALL_YES)

        assertEquals("test" to 1, result.framework to result.catalogueVersion)
    }

    @Test
    fun `every control that is not compliant is a gap, with why and the requirements that point to it`() {
        val catalogue = domains(
            control("GR.1-B", BASIC), control("GR.2-B", BASIC), control("GR.3-B", BASIC), control("GR.4-B", BASIC),
        ).copy(
            requirements = listOf(
                Requirement("R-1", "ref", "t", listOf("GR.1-B", "GR.2-B")),
                Requirement("R-2", "ref", "t", listOf("GR.1-B")),
            ),
        )
        val answers = mapOf("GR.1-B-Q1" to PARTIAL, "GR.2-B-Q1" to NO, "GR.3-B-Q1" to UNKNOWN, "GR.4-B-Q1" to YES)

        val result = rules.evaluate(catalogue, BASIC, answers)

        assertEquals(
            listOf(
                Gap("GR.1-B", listOf("R-1", "R-2"), BASIC, ControlStatus.PARTIAL, GapReason.ANSWERED_PARTIAL),
                Gap("GR.2-B", listOf("R-1"), BASIC, GAP, GapReason.ANSWERED_NO),
                Gap("GR.3-B", emptyList(), BASIC, GAP, GapReason.ANSWERED_UNKNOWN),
            ),
            result.gaps,
        )
        assertNull(result.controls.single { it.controlId == "GR.4-B" }.reason)
    }

    @Test
    fun `one no outweighs unknown and partial answers in the same control`() {
        val catalogue = domains(control("GR.1-B", BASIC, questions = 3))
        val answers = mapOf("GR.1-B-Q1" to UNKNOWN, "GR.1-B-Q2" to PARTIAL, "GR.1-B-Q3" to NO)

        assertEquals(GapReason.ANSWERED_NO, rules.evaluate(catalogue, BASIC, answers).gaps.single().reason)
    }

    @Test
    fun `each domain has its own level and the overall level is the lowest, domains in catalogue order`() {
        val catalogue = domains(
            control("PR.1-B", BASIC), control("PR.1-S", SUBSTANTIAL),
            control("GR.1-B", BASIC), control("GR.1-S", SUBSTANTIAL),
        )
        val answers = yes(catalogue) + ("GR.1-S-Q1" to NO)

        val result = rules.evaluate(catalogue, SUBSTANTIAL, answers)

        assertEquals(
            listOf(DomainEvaluation("PR", SUBSTANTIAL, SUBSTANTIAL), DomainEvaluation("GR", BASIC, SUBSTANTIAL)),
            result.domains,
        )
        assertEquals(BASIC, result.currentLevel)
    }

    @Test
    fun `a domain level is null when its basic level is not met`() {
        val catalogue = domains(control("GR.1-B", BASIC), control("GR.1-S", SUBSTANTIAL), control("PR.1-B", BASIC))

        val result = rules.evaluate(catalogue, SUBSTANTIAL, yes(catalogue) + ("GR.1-B-Q1" to UNKNOWN))

        assertEquals(listOf(null, SUBSTANTIAL), result.domains.map { it.currentLevel })
        assertNull(result.currentLevel)
    }

    @Test
    fun `a level with no controls in a domain counts as met there, but a level with none at all is skipped`() {
        val catalogue = domains(
            control("GR.1-B", BASIC), control("GR.1-E", HIGH), control("PR.1-B", BASIC), control("PR.1-S", SUBSTANTIAL),
        )

        val result = rules.evaluate(catalogue, HIGH, yes(catalogue))

        assertEquals(listOf(HIGH, HIGH), result.domains.map { it.currentLevel })
        assertEquals(HIGH, result.currentLevel)
        val noSubstantial = catalogue.copy(controls = catalogue.controls.filterNot { it.level == SUBSTANTIAL })
        assertEquals(listOf(BASIC, BASIC), rules.evaluate(noSubstantial, SUBSTANTIAL, yes(noSubstantial)).domains.map { it.currentLevel })
    }

    @Test
    fun `the same answers in any order give an equal result`() {
        val catalogue = domains(
            control("PR.1-B", BASIC, questions = 3), control("GR.1-B", BASIC, questions = 2),
            control("GR.1-S", SUBSTANTIAL), control("DE.1-E", HIGH),
        ).copy(requirements = listOf(Requirement("R-1", "ref", "t", listOf("GR.1-S", "PR.1-B"))))
        val answers = yes(catalogue) + mapOf("PR.1-B-Q2" to PARTIAL, "GR.1-S-Q1" to NO, "DE.1-E-Q1" to UNKNOWN)
        val shuffled = answers.entries.shuffled(java.util.Random(42)).associate { it.key to it.value }

        assertEquals(rules.evaluate(catalogue, HIGH, answers), rules.evaluate(catalogue, HIGH, java.util.HashMap(shuffled)))
    }

    @Test
    fun `levels follow the catalogue's own scale, by position`() {
        val scale = listOf(Level("1", "Inicial"), Level("2", "Gerido"), Level("3", "Definido"))
        val catalogue = domains(
            control("BUS.1", "1"), control("BUS.2", "2"), control("BUS.3", "3"),
            control("PEO.1", "1"), control("PEO.2", "2"),
        ).copy(levels = scale)

        val result = rules.evaluate(catalogue, "2", yes(catalogue) + ("PEO.2-Q1" to NO))

        assertEquals(listOf("BUS.1", "BUS.2", "PEO.1", "PEO.2"), result.controls.map { it.controlId })
        assertEquals(listOf(DomainEvaluation("BUS", "2", "2"), DomainEvaluation("PEO", "1", "2")), result.domains)
        assertEquals("1", result.currentLevel)
    }

    @Test
    fun `a target level outside the catalogue's scale is refused`() {
        val error = assertThrows<IllegalArgumentException> { rules.evaluate(CATALOGUE, "3", ALL_YES) }

        assertEquals(true, error.message?.contains("'3'"))
    }

    @Test
    fun `a numbered scale starting above 0 reports 0 when its first level is not met`() {
        val maturity = listOf(Level("1", "Inicial"), Level("2", "Gerido"))
        val catalogue = domains(control("BUS.1", "1"), control("BUS.2", "2"), control("PEO.1", "1"))
            .copy(levels = maturity)

        val result = rules.evaluate(catalogue, "2", yes(catalogue) + ("PEO.1-Q1" to PARTIAL))

        assertEquals(listOf(DomainEvaluation("BUS", "2", "2"), DomainEvaluation("PEO", "0", "2")), result.domains)
        assertEquals("0", result.currentLevel)
    }

    private fun domains(vararg controls: Control) = CATALOGUE.copy(controls = controls.toList())

    private fun yes(catalogue: Catalogue) = catalogue.controls.flatMap { it.questions }.associate { it.id to YES }
}
