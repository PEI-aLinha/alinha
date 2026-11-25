package pt.alinha.core.compliance

data class EvaluationInput(
    val catalogue: Catalogue,
    /** Controls above this level are not evaluated. Set by the caller (e.g. from the NIS2 classification). */
    val targetLevel: ComplianceLevel,
    /** Question id -> answer. Answers to questions outside the evaluated controls are ignored. */
    val answers: Map<String, Answer>,
)

/** Framework-neutral outcome for one control (GLOSSARY: control status). */
enum class ControlStatus { MET, PARTIAL, NOT_MET, UNKNOWN }

/** Why a control is a gap. */
enum class GapReason { ANSWERED_NO, ANSWERED_PARTIAL, NOT_ANSWERED }

data class ControlResult(
    val controlId: String,
    val domain: String,
    val level: ComplianceLevel,
    val status: ControlStatus,
    /** Null only when [status] is MET. */
    val reason: GapReason?,
)

/** A control whose status is anything other than met. */
data class Gap(
    val controlId: String,
    /** Requirements of the catalogue that point to this control, in catalogue order. */
    val requirementIds: List<String>,
    val level: ComplianceLevel,
    val status: ControlStatus,
    val reason: GapReason,
)

data class DomainResult(
    val domain: String,
    /** Highest level fully met, or null when not even the lowest level is met. */
    val currentLevel: ComplianceLevel?,
    val targetLevel: ComplianceLevel,
)

data class EvaluationResult(
    val rulesVersion: String,
    val framework: String,
    val catalogueVersion: Int,
    val targetLevel: ComplianceLevel,
    /** Lowest current level of all domains. */
    val currentLevel: ComplianceLevel?,
    /** Met controls / evaluated controls: progress, never a verdict of compliance. 0.0 when nothing is evaluated. */
    val coverage: Double,
    /** In catalogue order of first appearance. */
    val domains: List<DomainResult>,
    /** Evaluated controls, in catalogue order. */
    val controls: List<ControlResult>,
    val gaps: List<Gap>,
)
