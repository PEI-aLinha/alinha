package pt.alinha.core.compliance

/**
 * What the organization tells us for the applicability check (FR02, FR03). Rules: [ApplicabilityRules],
 * explained in docs/applicability.md. Assumes the organization is established in Portugal.
 */
data class ApplicabilityInput(
    val size: SizeData,
    /** Public administration body or other public entity (RJC art. 3.º n.os 3 and 4; RGPD art. 37.º n.º 1 a)). */
    val publicBody: Boolean = false,
    val nis2: Nis2Answers,
    val gdpr: GdprAnswers,
    val aiAct: AiActAnswers,
)

/** Size figures of the organization itself (RJC annex III). Partner and linked companies are not added. */
data class SizeData(
    val employees: Int,
    val annualTurnoverEur: Long,
    /** Optional: when known, a company can stay in a smaller category through its balance sheet. */
    val annualBalanceSheetEur: Long? = null,
) {
    init {
        require(employees >= 0) { "employees must not be negative" }
        require(annualTurnoverEur >= 0) { "annualTurnoverEur must not be negative" }
        require(annualBalanceSheetEur == null || annualBalanceSheetEur >= 0) {
            "annualBalanceSheetEur must not be negative"
        }
    }
}

data class Nis2Answers(
    /** Types from annexes I and II that describe the organization. Empty when none does. */
    val entityTypes: Set<Nis2EntityType> = emptySet(),
    /** Identified as a critical entity under Directive (EU) 2022/2557 (RJC art. 3.º n.º 5). */
    val criticalEntity: Boolean = false,
    /**
     * Sole provider of an essential service, or a disruption of its service could seriously affect public safety,
     * security or health, create systemic risks or matter nationally or regionally (RJC art. 3.º n.º 2 b) to e)).
     */
    val significantImpact: Boolean = false,
    /** Higher-education institution, public or private (RJC art. 3.º n.º 6). */
    val higherEducation: Boolean = false,
)

data class GdprAnswers(
    /** Handles information about people: clients, employees, contacts (RGPD art. 2.º n.º 1, art. 4.º 1) and 2)). */
    val processesPersonalData: Boolean,
    /** Processing is regular (not occasional) or may put people's rights at risk (RGPD art. 30.º n.º 5). */
    val regularOrRiskyProcessing: Boolean = false,
    /** Health, biometric, genetic or other special data, or data on criminal convictions (RGPD arts. 9.º and 10.º). */
    val specialOrCriminalData: Boolean = false,
    /** Core activities need regular and systematic monitoring of people on a large scale (RGPD art. 37.º n.º 1 b)). */
    val largeScaleMonitoring: Boolean = false,
    /** Core activities process special or criminal data on a large scale (RGPD art. 37.º n.º 1 c)). */
    val largeScaleSpecialData: Boolean = false,
) {
    init {
        require(
            processesPersonalData ||
                !(regularOrRiskyProcessing || specialOrCriminalData || largeScaleMonitoring || largeScaleSpecialData),
        ) { "Contradictory answers: details about personal data, but processesPersonalData is false" }
    }
}

data class AiActAnswers(
    /** Uses AI systems at work under its own authority: deployer (AI Act art. 3.º 4)). */
    val usesAiSystems: Boolean = false,
    /** Develops AI systems and offers them under its own name or brand: provider (AI Act art. 3.º 3)). */
    val providesAiSystems: Boolean = false,
    /** Uses or provides AI in an annex III area: recruitment, credit, insurance, education, biometrics, ... */
    val highRiskArea: Boolean = false,
    /** AI talks directly to people (e.g. a chatbot) or generates published images, audio, video or text. */
    val interactsOrGeneratesContent: Boolean = false,
) {
    init {
        require(usesAiSystems || providesAiSystems || !(highRiskArea || interactsOrGeneratesContent)) {
            "Contradictory answers: details about AI use, but neither usesAiSystems nor providesAiSystems is true"
        }
    }
}

/** Company size category (RJC annex III, art. 2.º, matching Recommendation 2003/361/EC). */
enum class CompanySize { MICRO, SMALL, MEDIUM, LARGE }

/** Indicative NIS2 classification. The official one is made by the CNCS (RJC art. 8.º). */
enum class Nis2Classification { ESSENTIAL, IMPORTANT, NOT_COVERED, TO_BE_ASSESSED }

enum class Applies {
    YES,
    NO,

    /** Depends on facts the rules cannot judge: a consultant (or the CNCS, for NIS2) decides. */
    REVIEW,
}

/**
 * One conclusion, with its legal basis and a plain-language explanation for the user (FR03).
 * [review] marks conclusions a consultant must confirm.
 */
data class Finding(
    val id: String,
    val legalBasis: String,
    val explanation: String,
    val review: Boolean = false,
)

data class FrameworkApplicability(
    /** Framework id, as in frameworks/<id>/: nis2-qnrcs, gdpr, ai-act. */
    val framework: String,
    val applies: Applies,
    val findings: List<Finding>,
)

data class ApplicabilityResult(
    /** Version of the rules that produced this result: store it with the result (RNF05). */
    val rulesVersion: String,
    val size: CompanySize,
    val nis2Classification: Nis2Classification,
    /** The CNCS may still qualify the organization, or qualify it higher (RJC art. 3.º n.º 2 b) to e), art. 6.º). */
    val cncsMayQualify: Boolean,
    val frameworks: List<FrameworkApplicability>,
    /**
     * The official classification from the CNCS Qualification Act, when registered (set by [ApplicabilityQuery], never
     * by the rules). It overrides [nis2Classification], which stays the indicative one.
     */
    val officialNis2Classification: Nis2Classification? = null,
) {
    /** The official classification when there is one, else the indicative one. */
    val effectiveNis2Classification: Nis2Classification get() = officialNis2Classification ?: nis2Classification

    /** Always true: these are suggestions, not the official qualification (shown to the user). */
    val indicative: Boolean = true
}
