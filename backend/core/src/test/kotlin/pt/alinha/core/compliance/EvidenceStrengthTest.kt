package pt.alinha.core.compliance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pt.alinha.core.compliance.Answer.NO
import pt.alinha.core.compliance.Answer.PARTIAL
import pt.alinha.core.compliance.Answer.YES
import pt.alinha.core.compliance.EvidenceStrength.CONTINUOUSLY_VERIFIED
import pt.alinha.core.compliance.EvidenceStrength.DOCUMENT_VERIFIED
import pt.alinha.core.compliance.EvidenceStrength.SELF_REPORTED
import pt.alinha.core.compliance.EvidenceStrength.TECHNICALLY_VERIFIED
import pt.alinha.core.compliance.TestCatalogues.BASIC
import pt.alinha.core.compliance.TestCatalogues.HIGH
import pt.alinha.core.compliance.TestCatalogues.answerAll
import pt.alinha.core.compliance.TestCatalogues.catalogue
import pt.alinha.core.compliance.TestCatalogues.control

/** The evidence strength gate (#26, docs/compliance-engine.md). */
class EvidenceStrengthTest {

    private val engine = ControlRules()

    // The bar comes from the catalogue's scale (here the implicit QNRCS one), never from the framework id.
    private val qnrcs = catalogue(
        control("PR.1-B", BASIC), control("PR.1-E", HIGH), control("PR.2-E", HIGH, technical = true),
        levels = QNRCS_LEVELS,
    )

    private val socCmm = catalogue(
        control("TEC.3", "3", technical = true), control("BUS.4", "4"), control("TEC.4", "4", technical = true),
        levels = (1..3).map { Level("$it", "L$it") } +
            (4..5).map { Level("$it", "L$it", DOCUMENT_VERIFIED, TECHNICALLY_VERIFIED) },
    )

    private fun evaluate(
        catalogue: Catalogue,
        evidence: List<Evidence> = emptyList(),
        answers: Map<String, Answer> = answerAll(catalogue, YES),
        target: String = catalogue.levels.last().id,
    ) = engine.evaluate(catalogue, target, answers, evidence)

    private fun Evaluation.of(controlId: String) = controls.single { it.controlId == controlId }

    private fun evidence(controlId: String, strength: EvidenceStrength, satisfied: Boolean = true) =
        Evidence(controlId, strength, satisfied, "ref-$controlId")

    @Test
    fun `a self-reported yes on a basic control is met`() {
        val result = evaluate(qnrcs).of("PR.1-B")

        assertThat(result.status).isEqualTo(ControlStatus.COMPLIANT)
        assertThat(result.strength).isEqualTo(SELF_REPORTED)
    }

    @Test
    fun `failing technical evidence overrides a yes`() {
        val result = evaluate(qnrcs, listOf(evidence("PR.1-B", TECHNICALLY_VERIFIED, satisfied = false))).of("PR.1-B")

        assertThat(result).extracting("status", "reason").containsExactly(ControlStatus.GAP, GapReason.EVIDENCE_FAILED)
    }

    @Test
    fun `failing self-reported or document evidence does not override the answer`() {
        val failing = listOf(
            evidence("PR.1-B", SELF_REPORTED, satisfied = false), evidence("PR.1-B", DOCUMENT_VERIFIED, satisfied = false),
        )

        assertThat(evaluate(qnrcs, failing).of("PR.1-B").status).isEqualTo(ControlStatus.COMPLIANT)
    }

    @Test
    fun `passing evidence never upgrades a partial or no answer`() {
        val answers = answerAll(qnrcs, YES) + mapOf("PR.1-B-Q1" to PARTIAL, "PR.1-E-Q1" to NO)
        val passing = listOf(evidence("PR.1-B", CONTINUOUSLY_VERIFIED), evidence("PR.1-E", CONTINUOUSLY_VERIFIED))

        val result = evaluate(qnrcs, passing, answers)

        assertThat(result.of("PR.1-B").status).isEqualTo(ControlStatus.PARTIAL)
        assertThat(result.of("PR.1-E").status).isEqualTo(ControlStatus.GAP)
        assertThat(result.of("PR.1-B").strength).isEqualTo(CONTINUOUSLY_VERIFIED)
    }

    @Test
    fun `a high control with only self-reported answers is partial for weak evidence`() {
        val result = evaluate(qnrcs)

        assertThat(result.of("PR.1-E")).extracting("status", "reason")
            .containsExactly(ControlStatus.PARTIAL, GapReason.WEAK_EVIDENCE)
        assertThat(result.gaps.map { it.controlId to it.reason })
            .contains("PR.1-E" to GapReason.WEAK_EVIDENCE)
    }

    @Test
    fun `a high control with document evidence is met`() {
        val result = evaluate(qnrcs, listOf(evidence("PR.1-E", DOCUMENT_VERIFIED))).of("PR.1-E")

        assertThat(result.status).isEqualTo(ControlStatus.COMPLIANT)
        assertThat(result.strength).isEqualTo(DOCUMENT_VERIFIED)
    }

    @Test
    fun `a technical high control needs technical evidence, and continuous evidence is enough`() {
        assertThat(evaluate(qnrcs, listOf(evidence("PR.2-E", DOCUMENT_VERIFIED))).of("PR.2-E").reason)
            .isEqualTo(GapReason.WEAK_EVIDENCE)
        assertThat(evaluate(qnrcs, listOf(evidence("PR.2-E", TECHNICALLY_VERIFIED))).of("PR.2-E").status)
            .isEqualTo(ControlStatus.COMPLIANT)
        assertThat(evaluate(qnrcs, listOf(evidence("PR.2-E", CONTINUOUSLY_VERIFIED))).of("PR.2-E").status)
            .isEqualTo(ControlStatus.COMPLIANT)
    }

    @Test
    fun `soc-cmm up to level 3 accepts self-reported answers, level 4 needs a document`() {
        val result = evaluate(socCmm)

        assertThat(result.of("TEC.3").status).isEqualTo(ControlStatus.COMPLIANT)
        assertThat(result.of("BUS.4").reason).isEqualTo(GapReason.WEAK_EVIDENCE)
        assertThat(evaluate(socCmm, listOf(evidence("BUS.4", DOCUMENT_VERIFIED))).of("BUS.4").status)
            .isEqualTo(ControlStatus.COMPLIANT)
        assertThat(evaluate(socCmm, listOf(evidence("TEC.4", DOCUMENT_VERIFIED))).of("TEC.4").reason)
            .isEqualTo(GapReason.WEAK_EVIDENCE)
    }

    @Test
    fun `evidence for controls that are not evaluated is ignored`() {
        val failing = listOf(
            evidence("XX.9-B", TECHNICALLY_VERIFIED, satisfied = false),
            evidence("PR.1-E", TECHNICALLY_VERIFIED, satisfied = false),
        )

        val result = evaluate(qnrcs, failing, target = BASIC)

        assertThat(result.controls.map { it.controlId to it.status }).containsExactly("PR.1-B" to ControlStatus.COMPLIANT)
    }

    @Test
    fun `a scale that declares no bar accepts self-reported answers at every level`() {
        val plain = catalogue(control("BUS.5", "5", technical = true), levels = (1..5).map { Level("$it", "L$it") })

        assertThat(evaluate(plain).of("BUS.5").status).isEqualTo(ControlStatus.COMPLIANT)
    }
}
