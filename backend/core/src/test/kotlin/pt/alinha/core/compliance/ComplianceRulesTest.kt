package pt.alinha.core.compliance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pt.alinha.core.compliance.Answer.NO
import pt.alinha.core.compliance.Answer.PARTIAL
import pt.alinha.core.compliance.Answer.UNKNOWN
import pt.alinha.core.compliance.Answer.YES
import pt.alinha.core.compliance.ComplianceLevel.BASIC
import pt.alinha.core.compliance.ComplianceLevel.HIGH
import pt.alinha.core.compliance.ComplianceLevel.SUBSTANTIAL
import pt.alinha.core.compliance.TestCatalogues.answerAll
import pt.alinha.core.compliance.TestCatalogues.catalogue
import pt.alinha.core.compliance.TestCatalogues.control

class ComplianceRulesTest {

    private val engine = ComplianceRules()

    private fun evaluate(catalogue: Catalogue, answers: Map<String, Answer>, target: ComplianceLevel = HIGH) =
        engine.evaluate(EvaluationInput(catalogue, target, answers))

    private fun EvaluationResult.status(controlId: String) = controls.single { it.controlId == controlId }

    @Test
    fun `a control with every question answered yes is met`() {
        val cat = catalogue(control("GR.1-B", BASIC, questions = 2))

        val result = evaluate(cat, answerAll(cat, YES))

        assertThat(result.status("GR.1-B").status).isEqualTo(ControlStatus.MET)
        assertThat(result.status("GR.1-B").reason).isNull()
    }

    @Test
    fun `one no makes the control not met, even with unknown and partial answers`() {
        val cat = catalogue(control("GR.1-B", BASIC, questions = 4))

        val answers = mapOf("GR.1-B-Q1" to YES, "GR.1-B-Q2" to PARTIAL, "GR.1-B-Q3" to UNKNOWN, "GR.1-B-Q4" to NO)

        val result = evaluate(cat, answers)

        assertThat(result.status("GR.1-B")).extracting("status", "reason")
            .containsExactly(ControlStatus.NOT_MET, GapReason.ANSWERED_NO)
    }

    @Test
    fun `a missing answer makes the control unknown, the same as answering unknown`() {
        val cat = catalogue(control("GR.1-B", BASIC, questions = 2), control("GR.2-B", BASIC))

        val result = evaluate(cat, mapOf("GR.1-B-Q1" to PARTIAL, "GR.2-B-Q1" to UNKNOWN))

        assertThat(result.status("GR.1-B")).extracting("status", "reason")
            .containsExactly(ControlStatus.UNKNOWN, GapReason.NOT_ANSWERED)
        assertThat(result.status("GR.2-B")).extracting("status", "reason")
            .containsExactly(ControlStatus.UNKNOWN, GapReason.NOT_ANSWERED)
    }

    @Test
    fun `a partial answer with the rest yes makes the control partial`() {
        val cat = catalogue(control("GR.1-B", BASIC, questions = 2))

        val result = evaluate(cat, mapOf("GR.1-B-Q1" to YES, "GR.1-B-Q2" to PARTIAL))

        assertThat(result.status("GR.1-B")).extracting("status", "reason")
            .containsExactly(ControlStatus.PARTIAL, GapReason.ANSWERED_PARTIAL)
    }

    @Test
    fun `controls above the target level are not evaluated`() {
        val cat = catalogue(control("GR.1-B", BASIC), control("GR.1-S", SUBSTANTIAL), control("GR.1-E", HIGH))

        val result = evaluate(cat, answerAll(cat, YES), target = SUBSTANTIAL)

        assertThat(result.controls.map { it.controlId }).containsExactly("GR.1-B", "GR.1-S")
    }

    @Test
    fun `answers to questions outside the evaluated controls are ignored`() {
        val cat = catalogue(control("GR.1-B", BASIC), control("GR.1-S", SUBSTANTIAL))

        val result = evaluate(cat, mapOf("GR.1-B-Q1" to YES, "GR.1-S-Q1" to NO, "XX.9-B-Q1" to NO), target = BASIC)

        assertThat(result.controls).extracting("status").containsExactly(ControlStatus.MET)
        assertThat(result.gaps).isEmpty()
    }

    @Test
    fun `coverage counts only met controls over the evaluated ones`() {
        val cat = catalogue(
            control("GR.1-B", BASIC), control("GR.2-B", BASIC), control("GR.3-B", BASIC), control("GR.4-B", BASIC),
        )

        val result = evaluate(cat, mapOf("GR.1-B-Q1" to YES, "GR.2-B-Q1" to PARTIAL, "GR.3-B-Q1" to NO))

        assertThat(result.coverage).isEqualTo(0.25)
    }

    @Test
    fun `a gap lists the requirements that point to the control`() {
        val cat = catalogue(
            control("GR.1-B", BASIC), control("GR.2-B", BASIC),
            requirements = listOf(
                Requirement("R-1", "ref", "t", listOf("GR.1-B", "GR.2-B")),
                Requirement("R-2", "ref", "t", listOf("GR.1-B")),
                Requirement("R-3", "ref", "t", listOf("GR.2-B")),
            ),
        )

        val result = evaluate(cat, mapOf("GR.1-B-Q1" to PARTIAL, "GR.2-B-Q1" to YES))

        assertThat(result.gaps).containsExactly(
            Gap("GR.1-B", listOf("R-1", "R-2"), BASIC, ControlStatus.PARTIAL, GapReason.ANSWERED_PARTIAL),
        )
    }

    @Test
    fun `the result carries the rules version, the catalogue version and the target level`() {
        val cat = catalogue(control("GR.1-B", BASIC))

        val result = evaluate(cat, emptyMap(), target = SUBSTANTIAL)

        assertThat(result).extracting("rulesVersion", "framework", "catalogueVersion", "targetLevel")
            .containsExactly("compliance-rules/1", "test-fw", 3, SUBSTANTIAL)
    }

    @Test
    fun `the domain level stops at the first level that is not fully met`() {
        val cat = catalogue(
            control("GR.1-B", BASIC), control("GR.1-S", SUBSTANTIAL), control("GR.2-S", SUBSTANTIAL),
            control("GR.1-E", HIGH),
        )

        val result = evaluate(cat, answerAll(cat, YES) + ("GR.2-S-Q1" to PARTIAL))

        assertThat(result.domains).containsExactly(DomainResult("GR", BASIC, HIGH))
    }

    @Test
    fun `the domain level is null when the basic level is not met`() {
        val cat = catalogue(control("GR.1-B", BASIC), control("GR.1-S", SUBSTANTIAL))

        val result = evaluate(cat, answerAll(cat, YES) + ("GR.1-B-Q1" to UNKNOWN), target = SUBSTANTIAL)

        assertThat(result.domains.single().currentLevel).isNull()
        assertThat(result.currentLevel).isNull()
    }

    @Test
    fun `the domain level never goes above the target`() {
        val cat = catalogue(control("GR.1-B", BASIC), control("GR.1-S", SUBSTANTIAL), control("GR.1-E", HIGH))

        val result = evaluate(cat, answerAll(cat, YES), target = SUBSTANTIAL)

        assertThat(result.domains.single().currentLevel).isEqualTo(SUBSTANTIAL)
    }

    @Test
    fun `a level with no controls in the domain counts as met`() {
        val cat = catalogue(control("GR.1-B", BASIC), control("GR.1-E", HIGH))

        val result = evaluate(cat, answerAll(cat, YES))

        assertThat(result.domains.single().currentLevel).isEqualTo(HIGH)
    }

    @Test
    fun `the overall level is the lowest domain level, domains in catalogue order`() {
        val cat = catalogue(
            control("PR.1-B", BASIC), control("PR.1-S", SUBSTANTIAL),
            control("GR.1-B", BASIC), control("GR.1-S", SUBSTANTIAL),
        )

        val result = evaluate(cat, answerAll(cat, YES) + ("GR.1-S-Q1" to NO), target = SUBSTANTIAL)

        assertThat(result.domains).containsExactly(
            DomainResult("PR", SUBSTANTIAL, SUBSTANTIAL),
            DomainResult("GR", BASIC, SUBSTANTIAL),
        )
        assertThat(result.currentLevel).isEqualTo(BASIC)
    }

    @Test
    fun `the same answers in any order give an equal result`() {
        val cat = catalogue(
            control("PR.1-B", BASIC, questions = 3), control("GR.1-B", BASIC, questions = 2),
            control("GR.1-S", SUBSTANTIAL), control("DE.1-E", HIGH),
            requirements = listOf(Requirement("R-1", "ref", "t", listOf("GR.1-S", "PR.1-B"))),
        )
        val answers = answerAll(cat, YES) + mapOf("PR.1-B-Q2" to PARTIAL, "GR.1-S-Q1" to NO, "DE.1-E-Q1" to UNKNOWN)
        val shuffled = answers.entries.shuffled(java.util.Random(42)).associate { it.key to it.value }

        assertThat(evaluate(cat, java.util.HashMap(shuffled))).isEqualTo(evaluate(cat, answers))
    }
}
