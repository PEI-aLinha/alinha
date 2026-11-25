package pt.alinha.core.compliance

data class EvaluationInput(
    val catalogue: Catalogue,
    /**
     * Id of a level of the catalogue's scale: controls above it are not evaluated. Set by the caller (e.g. from the
     * NIS2 classification).
     */
    val targetLevel: String,
    /** Question id -> answer. Answers to questions outside the evaluated controls are ignored. */
    val answers: Map<String, Answer>,
    /** Evidence per control (the caller maps actions to controls). Evidence for controls not evaluated is ignored. */
    val evidence: List<Evidence> = emptyList(),
)

/** How far evidence can be trusted, weakest first (GLOSSARY: evidence strength). */
enum class EvidenceStrength { SELF_REPORTED, DOCUMENT_VERIFIED, TECHNICALLY_VERIFIED, CONTINUOUSLY_VERIFIED }

/** One piece of evidence for a control: whether it shows the control in place, and how far it can be trusted. */
data class Evidence(
    val controlId: String,
    val strength: EvidenceStrength,
    val satisfied: Boolean,
    /** Where the evidence is (e.g. a collected result or a document id), for traceability. */
    val ref: String,
)

/** Framework-neutral outcome for one control (GLOSSARY: control status). */
enum class ControlStatus { MET, PARTIAL, NOT_MET, UNKNOWN }

/** Why a control is a gap. */
enum class GapReason {
    ANSWERED_NO,
    ANSWERED_PARTIAL,
    NOT_ANSWERED,

    /** Answered yes, but the evidence is weaker than the control's level requires. */
    WEAK_EVIDENCE,

    /** Evidence technically verified or stronger shows the control is not in place. */
    EVIDENCE_FAILED,
}

/** Levels are ids of the catalogue's level scale (ADR 0007). */
data class ControlResult(
    val controlId: String,
    val domain: String,
    val level: String,
    val status: ControlStatus,
    /** Null only when [status] is MET. */
    val reason: GapReason?,
    /** Strongest passing evidence; SELF_REPORTED (the answers) when there is none. */
    val strength: EvidenceStrength = EvidenceStrength.SELF_REPORTED,
)

/** A control whose status is anything other than met. */
data class Gap(
    val controlId: String,
    /** Requirements of the catalogue that point to this control, in catalogue order. */
    val requirementIds: List<String>,
    val level: String,
    val status: ControlStatus,
    val reason: GapReason,
)

data class DomainResult(
    val domain: String,
    /**
     * Highest level fully met. When not even the lowest level is met: "0" on a numbered scale starting above 0
     * (SOC-CMM), else null (QNRCS).
     */
    val currentLevel: String?,
    val targetLevel: String,
)

data class EvaluationResult(
    val rulesVersion: String,
    val framework: String,
    val catalogueVersion: Int,
    val targetLevel: String,
    /** Lowest current level of all domains. */
    val currentLevel: String?,
    /** Met controls / evaluated controls: progress, never a verdict of compliance. 0.0 when nothing is evaluated. */
    val coverage: Double,
    /** In catalogue order of first appearance. */
    val domains: List<DomainResult>,
    /** Evaluated controls, in catalogue order. */
    val controls: List<ControlResult>,
    val gaps: List<Gap>,
)
