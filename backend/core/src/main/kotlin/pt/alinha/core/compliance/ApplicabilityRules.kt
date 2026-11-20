package pt.alinha.core.compliance

import org.springframework.stereotype.Component
import pt.alinha.core.compliance.CompanySize.LARGE
import pt.alinha.core.compliance.CompanySize.MEDIUM
import pt.alinha.core.compliance.CompanySize.MICRO
import pt.alinha.core.compliance.CompanySize.SMALL
import pt.alinha.core.compliance.Nis2Classification.ESSENTIAL
import pt.alinha.core.compliance.Nis2Classification.IMPORTANT
import pt.alinha.core.compliance.Nis2Classification.NOT_COVERED
import pt.alinha.core.compliance.Nis2Classification.TO_BE_ASSESSED

/**
 * Which frameworks apply to an organization and its indicative NIS2 classification (FR02, FR03, US11, US34).
 * Deterministic and versioned (RNF05): the same input always gives the same result for a [VERSION].
 * Sources: Decree-Law 125/2025 (RJC), Regulation (EU) 2016/679 (RGPD), Regulation (EU) 2024/1689 (AI Act).
 * Every rule is listed, with its article, in docs/applicability.md.
 */
@Component
class ApplicabilityRules {

    fun assess(input: ApplicabilityInput): ApplicabilityResult {
        val size = companySize(input.size)
        val nis2 = nis2(input, size)
        return ApplicabilityResult(
            rulesVersion = VERSION,
            size = size,
            nis2Classification = nis2.classification,
            cncsMayQualify = nis2.cncsMayQualify,
            frameworks = listOf(nis2.framework, gdpr(input), aiAct(input.aiAct)),
        )
    }

    /** RJC annex III, art. 2.º: headcount AND (turnover OR balance sheet) under the limit. */
    fun companySize(size: SizeData): CompanySize {
        fun withinFinancialLimits(turnover: Long, balanceSheet: Long) =
            size.annualTurnoverEur <= turnover || (size.annualBalanceSheetEur?.let { it <= balanceSheet } ?: false)

        return when {
            size.employees < 10 && withinFinancialLimits(2 * MILLION, 2 * MILLION) -> MICRO
            size.employees < 50 && withinFinancialLimits(10 * MILLION, 10 * MILLION) -> SMALL
            size.employees < 250 && withinFinancialLimits(50 * MILLION, 43 * MILLION) -> MEDIUM
            else -> LARGE
        }
    }

    // --- NIS2 (Decree-Law 125/2025, Regime Jurídico da Cibersegurança) ---

    private class Nis2Outcome(
        val classification: Nis2Classification,
        val cncsMayQualify: Boolean,
        val framework: FrameworkApplicability,
    )

    private class TypeOutcome(val classification: Nis2Classification, val finding: Finding)

    private fun nis2(input: ApplicabilityInput, size: CompanySize): Nis2Outcome {
        val answers = input.nis2
        val findings = mutableListOf(sizeFinding(size))

        // A critical entity is essential at any size, public ones included (art. 3.º n.º 5, art. 6.º n.º 1 e)),
        // so it is checked before the public-sector case below.
        if ((input.publicBody || answers.higherEducation) && !answers.criticalEntity) {
            findings += Finding(
                "nis2.public-sector", "RJC, art. 3.º, n.os 3 a 6, e art. 7.º",
                "Entidades públicas e instituições de ensino superior têm regras próprias. " +
                    "O consultor confirma a classificação com o CNCS.",
                review = true,
            )
            return Nis2Outcome(TO_BE_ASSESSED, true, FrameworkApplicability(NIS2, Applies.REVIEW, findings))
        }

        val outcomes = when {
            answers.criticalEntity -> listOf(
                TypeOutcome(
                    ESSENTIAL,
                    Finding(
                        "nis2.critical-entity", "RJC, art. 3.º, n.º 5, e art. 6.º, n.º 1, alínea e)",
                        "Foi identificada como entidade crítica, por isso é entidade essencial, seja qual for a dimensão.",
                    ),
                ),
            )
            answers.entityTypes.isEmpty() -> listOf(
                TypeOutcome(
                    NOT_COVERED,
                    Finding(
                        "nis2.no-annex-type", "RJC, art. 3.º, n.º 1, e anexos I e II",
                        "A atividade indicada não consta dos setores abrangidos pelo regime de cibersegurança.",
                    ),
                ),
            )
            // Annex order, then most demanding first (stable sort): the same input always gives the same findings in
            // the same order, whatever Set implementation it arrived in (RNF05). Every type keeps its finding (FR03).
            else -> answers.entityTypes.sortedBy { it.ordinal }.map { classify(it, size) }
                .sortedByDescending { rank(it.classification) }
        }
        // The most demanding classification wins (art. 9.º).
        val classification = outcomes.first().classification
        findings += outcomes.map { it.finding }

        // The CNCS may still cover a small entity, or raise an important one to essential (art. 3.º n.º 2 b-e).
        val cncsMayQualify = answers.significantImpact && answers.entityTypes.isNotEmpty() &&
            classification != ESSENTIAL
        if (cncsMayQualify) {
            findings += Finding(
                "nis2.cncs-may-qualify", "RJC, art. 3.º, n.º 2, alíneas b) a e), e art. 6.º, n.º 1, alínea f), e n.º 3",
                "Como o serviço que presta é muito importante para outros, o CNCS pode abranger a organização " +
                    "ou subir a sua classificação, mesmo que seja pequena. O consultor confirma.",
                review = true,
            )
        }

        // Art. 3.º n.º 2 covers only annex types, but an organization claiming significant impact with no type picked
        // has probably not found its type in the list: a consultant checks instead of answering "not covered".
        val checkAnnexType = answers.significantImpact && answers.entityTypes.isEmpty() &&
            classification == NOT_COVERED
        if (checkAnnexType) {
            findings += Finding(
                "nis2.check-annex-type", "RJC, art. 3.º, n.º 2, e anexos I e II",
                "Indicou que o serviço que presta é muito importante para outros, mas não escolheu nenhum tipo de " +
                    "entidade da lista. O regime só abrange os tipos da lista: o consultor confirma se a sua " +
                    "atividade lá consta.",
                review = true,
            )
        }

        val applies = when {
            classification != NOT_COVERED -> Applies.YES
            cncsMayQualify || checkAnnexType -> Applies.REVIEW
            else -> Applies.NO
        }
        return Nis2Outcome(classification, cncsMayQualify, FrameworkApplicability(NIS2, applies, findings))
    }

    private fun classify(type: Nis2EntityType, size: CompanySize): TypeOutcome {
        val atLeastMedium = size == MEDIUM || size == LARGE
        fun outcome(classification: Nis2Classification, rule: String, legalBasis: String, explanation: String) =
            TypeOutcome(
                classification,
                Finding("nis2.${classification.name.lowercase()}.$rule", legalBasis, "${type.label}: $explanation"),
            )

        return when {
            type in ESSENTIAL_ANY_SIZE -> outcome(
                ESSENTIAL, "any-size", "RJC, art. 3.º, n.º 2, alínea a), e art. 6.º, n.º 1, alínea b)",
                "é sempre entidade essencial, seja qual for a dimensão.",
            )
            type in ELECTRONIC_COMMUNICATIONS && atLeastMedium -> outcome(
                ESSENTIAL, "communications", "RJC, art. 6.º, n.º 1, alíneas a) e c)",
                "sendo média ou grande empresa, é entidade essencial.",
            )
            type in ELECTRONIC_COMMUNICATIONS -> outcome(
                IMPORTANT, "communications-small", "RJC, art. 3.º, n.º 2, alínea a), e art. 6.º, n.º 2",
                "está abrangida mesmo sendo micro ou pequena empresa, como entidade importante.",
            )
            type == Nis2EntityType.TRUST_SERVICE_PROVIDER && size != LARGE -> outcome(
                IMPORTANT, "trust-service", "RJC, art. 3.º, n.º 2, alínea a), e art. 6.º, n.º 2",
                "está abrangida seja qual for a dimensão; não sendo grande empresa, é entidade importante.",
            )
            type.annex == Nis2Annex.I && size == LARGE -> outcome(
                ESSENTIAL, "large-annex-i", "RJC, art. 3.º, n.º 1, e art. 6.º, n.º 1, alínea a)",
                "é um setor de importância crítica e a organização é grande empresa, por isso é entidade essencial.",
            )
            atLeastMedium -> outcome(
                IMPORTANT, "medium-or-annex-ii", "RJC, art. 3.º, n.º 1, e art. 6.º, n.º 2",
                "é um setor abrangido e a organização é pelo menos média empresa, por isso é entidade importante.",
            )
            else -> outcome(
                NOT_COVERED, "small", "RJC, art. 3.º, n.º 1, alínea a)",
                "o setor é abrangido, mas as micro e pequenas empresas ficam de fora.",
            )
        }
    }

    private fun sizeFinding(size: CompanySize): Finding {
        val name = when (size) {
            MICRO -> "microempresa"
            SMALL -> "pequena empresa"
            MEDIUM -> "média empresa"
            LARGE -> "grande empresa"
        }
        return Finding(
            "nis2.size.${size.name.lowercase()}", "RJC, anexo III, art. 2.º (Recomendação 2003/361/CE)",
            "Pelo número de trabalhadores e pelo volume de negócios ou balanço, a organização é $name.",
        )
    }

    private fun rank(classification: Nis2Classification) = when (classification) {
        ESSENTIAL -> 3
        IMPORTANT -> 2
        TO_BE_ASSESSED -> 1
        NOT_COVERED -> 0
    }

    // --- RGPD (Regulation (EU) 2016/679) ---

    private fun gdpr(input: ApplicabilityInput): FrameworkApplicability {
        val answers = input.gdpr
        if (!answers.processesPersonalData) {
            return FrameworkApplicability(
                GDPR, Applies.NO,
                listOf(
                    Finding(
                        "gdpr.no-personal-data", "RGPD, art. 2.º, n.º 1",
                        "Indicou que não trata dados de pessoas, por isso o RGPD não se aplica.",
                    ),
                ),
            )
        }
        val findings = mutableListOf(
            Finding(
                "gdpr.applies", "RGPD, art. 2.º, n.º 1, art. 3.º, n.º 1, e art. 4.º, 1) e 2)",
                "Trata dados de pessoas (clientes, trabalhadores ou contactos), por isso o RGPD aplica-se.",
            ),
        )
        // Art. 30.º n.º 5: large-scale monitoring is never occasional, and large-scale special data is special data.
        val recordsRequired = input.size.employees >= 250 || answers.regularOrRiskyProcessing ||
            answers.specialOrCriminalData || answers.largeScaleMonitoring || answers.largeScaleSpecialData
        findings += if (recordsRequired) {
            Finding(
                "gdpr.records-required", "RGPD, art. 30.º, n.os 1 e 5",
                "Tem de manter um registo das atividades de tratamento: que dados trata, para quê e por quanto tempo.",
            )
        } else {
            Finding(
                "gdpr.records-exempt", "RGPD, art. 30.º, n.º 5",
                "Com menos de 250 trabalhadores e tratamento apenas ocasional, sem risco para as pessoas e sem dados sensíveis, " +
                    "não é obrigatório manter o registo das atividades de tratamento.",
            )
        }
        findings += if (input.publicBody || answers.largeScaleMonitoring || answers.largeScaleSpecialData) {
            Finding(
                "gdpr.dpo-required", "RGPD, art. 37.º, n.º 1",
                "Tem de nomear um encarregado da proteção de dados (DPO).",
            )
        } else {
            Finding(
                "gdpr.dpo-not-required", "RGPD, art. 37.º, n.º 1",
                "Pelas respostas dadas, não é obrigatório nomear um encarregado da proteção de dados (DPO).",
            )
        }
        return FrameworkApplicability(GDPR, Applies.YES, findings)
    }

    // --- AI Act (Regulation (EU) 2024/1689) ---

    private fun aiAct(answers: AiActAnswers): FrameworkApplicability {
        if (!answers.usesAiSystems && !answers.providesAiSystems) {
            return FrameworkApplicability(
                AI_ACT, Applies.NO,
                listOf(
                    Finding(
                        "ai-act.no-ai", "AI Act, art. 2.º, n.º 1",
                        "Indicou que não usa nem desenvolve sistemas de inteligência artificial.",
                    ),
                ),
            )
        }
        val findings = mutableListOf<Finding>()
        if (answers.providesAiSystems) {
            findings += Finding(
                "ai-act.provider", "AI Act, art. 2.º, n.º 1, alínea a), e art. 3.º, 3)",
                "Desenvolve sistemas de IA e disponibiliza-os com o seu nome: é prestador e tem as obrigações " +
                    "de quem coloca a IA no mercado.",
            )
        }
        if (answers.usesAiSystems) {
            findings += Finding(
                "ai-act.deployer", "AI Act, art. 2.º, n.º 1, alínea b), e art. 3.º, 4)",
                "Usa sistemas de IA no trabalho: é responsável pela implantação.",
            )
        }
        findings += Finding(
            "ai-act.ai-literacy", "AI Act, art. 4.º",
            "Tem de garantir que quem usa a IA na organização tem formação suficiente para o fazer.",
        )
        if (answers.highRiskArea) {
            findings += Finding(
                "ai-act.possible-high-risk", "AI Act, art. 6.º, n.os 2 e 3, e anexo III",
                "Usa ou desenvolve IA numa área sensível (por exemplo recrutamento, crédito, seguros ou educação). " +
                    "Pode ser um sistema de risco elevado, com obrigações adicionais. O consultor confirma.",
                review = true,
            )
        }
        // Art. 50.º splits the duties: providers (n.os 1 and 2) and deployers of emotion recognition, deep fakes
        // or text published on matters of public interest (n.os 3 and 4). Which apply depends on the case.
        if (answers.interactsOrGeneratesContent) {
            findings += Finding(
                "ai-act.transparency", "AI Act, art. 50.º",
                "Pode ter de avisar as pessoas de que estão a falar com uma IA, ou de identificar conteúdo gerado " +
                    "por IA (por exemplo, imagens ou vídeos realistas). O consultor confirma quais as obrigações.",
                review = true,
            )
        }
        // The AI Act applies either way; findings marked review (high risk, transparency) need a consultant.
        return FrameworkApplicability(AI_ACT, Applies.YES, findings)
    }

    companion object {
        /** Bump when any rule changes, so stored results say which rules produced them. */
        const val VERSION = "applicability-2026.11-v1"

        const val NIS2 = "nis2-qnrcs"
        const val GDPR = "gdpr"
        const val AI_ACT = "ai-act"

        private const val MILLION = 1_000_000L

        /** RJC art. 6.º n.º 1 b): essential regardless of size. */
        private val ESSENTIAL_ANY_SIZE = setOf(
            Nis2EntityType.QUALIFIED_TRUST_SERVICE_PROVIDER,
            Nis2EntityType.TLD_NAME_REGISTRY,
            Nis2EntityType.DNS_SERVICE_PROVIDER,
        )

        /** RJC art. 3.º n.º 2 a) i) and art. 6.º n.º 1 c). */
        private val ELECTRONIC_COMMUNICATIONS = setOf(
            Nis2EntityType.PUBLIC_ELECTRONIC_COMMUNICATIONS_NETWORK,
            Nis2EntityType.PUBLIC_ELECTRONIC_COMMUNICATIONS_SERVICE,
        )
    }
}
