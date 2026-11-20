package pt.alinha.core.compliance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pt.alinha.core.compliance.CompanySize.LARGE
import pt.alinha.core.compliance.CompanySize.MEDIUM
import pt.alinha.core.compliance.CompanySize.MICRO
import pt.alinha.core.compliance.CompanySize.SMALL
import pt.alinha.core.compliance.Nis2Classification.ESSENTIAL
import pt.alinha.core.compliance.Nis2Classification.IMPORTANT
import pt.alinha.core.compliance.Nis2Classification.NOT_COVERED
import pt.alinha.core.compliance.Nis2Classification.TO_BE_ASSESSED
import pt.alinha.core.compliance.Nis2EntityType.CHEMICALS
import pt.alinha.core.compliance.Nis2EntityType.DNS_SERVICE_PROVIDER
import pt.alinha.core.compliance.Nis2EntityType.DRINKING_WATER
import pt.alinha.core.compliance.Nis2EntityType.HEALTHCARE_PROVIDER
import pt.alinha.core.compliance.Nis2EntityType.PUBLIC_ELECTRONIC_COMMUNICATIONS_SERVICE
import pt.alinha.core.compliance.Nis2EntityType.QUALIFIED_TRUST_SERVICE_PROVIDER
import pt.alinha.core.compliance.Nis2EntityType.TLD_NAME_REGISTRY
import pt.alinha.core.compliance.Nis2EntityType.TRUST_SERVICE_PROVIDER

private const val M = 1_000_000L

private val MICRO_SIZE = SizeData(employees = 5, annualTurnoverEur = 1 * M)
private val SMALL_SIZE = SizeData(employees = 30, annualTurnoverEur = 5 * M)
private val MEDIUM_SIZE = SizeData(employees = 120, annualTurnoverEur = 20 * M)
private val LARGE_SIZE = SizeData(employees = 400, annualTurnoverEur = 80 * M)

class ApplicabilityRulesTest {

    private val rules = ApplicabilityRules()

    private fun assess(
        size: SizeData = MEDIUM_SIZE,
        nis2: Nis2Answers = Nis2Answers(),
        gdpr: GdprAnswers = GdprAnswers(processesPersonalData = true),
        aiAct: AiActAnswers = AiActAnswers(),
        publicBody: Boolean = false,
    ) = rules.assess(ApplicabilityInput(size, publicBody, nis2, gdpr, aiAct))

    private fun ApplicabilityResult.framework(id: String) = frameworks.single { it.framework == id }

    private fun ApplicabilityResult.findingIds(id: String) = framework(id).findings.map { it.id }

    private fun classify(size: SizeData, vararg types: Nis2EntityType) =
        assess(size, Nis2Answers(entityTypes = types.toSet())).nis2Classification

    @Nested
    inner class CompanySizeCategories {

        /** RJC annex III, art. 2.º: "menos de" employees, "não excede" money. */
        @ParameterizedTest(name = "{0} employees, turnover {1}, balance {2} -> {3}")
        @CsvSource(
            "9,   2000000,  ,         MICRO",
            "10,  2000000,  ,         SMALL",
            "9,   2000001,  ,         SMALL",
            "9,   2000001,  2000000,  MICRO",
            "49,  10000000, ,         SMALL",
            "50,  10000000, ,         MEDIUM",
            "49,  10000001, ,         MEDIUM",
            "249, 50000000, ,         MEDIUM",
            "250, 1000000,  ,         LARGE",
            "249, 50000001, ,         LARGE",
            "249, 50000001, 43000000, MEDIUM",
            "249, 50000001, 43000001, LARGE",
            "0,   0,        ,         MICRO",
        )
        fun `size follows the annex III limits`(employees: Int, turnover: Long, balance: Long?, expected: CompanySize) {
            assertEquals(expected, rules.companySize(SizeData(employees, turnover, balance)))
        }

        @Test
        fun `negative figures are rejected`() {
            assertThrows<IllegalArgumentException> { SizeData(employees = -1, annualTurnoverEur = 0) }
            assertThrows<IllegalArgumentException> { SizeData(employees = 1, annualTurnoverEur = -1) }
            assertThrows<IllegalArgumentException> { SizeData(1, 0, annualBalanceSheetEur = -1) }
        }
    }

    @Nested
    inner class Nis2 {

        @Test
        fun `no annex type means not covered`() {
            val result = assess(LARGE_SIZE)
            assertEquals(NOT_COVERED, result.nis2Classification)
            assertEquals(Applies.NO, result.framework(ApplicabilityRules.NIS2).applies)
        }

        @Test
        fun `annex I is essential when large and important when medium`() {
            assertEquals(ESSENTIAL, classify(LARGE_SIZE, HEALTHCARE_PROVIDER))
            assertEquals(IMPORTANT, classify(MEDIUM_SIZE, HEALTHCARE_PROVIDER))
        }

        @Test
        fun `annex II is important when at least medium, even when large`() {
            assertEquals(IMPORTANT, classify(MEDIUM_SIZE, CHEMICALS))
            assertEquals(IMPORTANT, classify(LARGE_SIZE, CHEMICALS))
        }

        @Test
        fun `micro and small entities of an annex type are not covered`() {
            assertEquals(NOT_COVERED, classify(SMALL_SIZE, DRINKING_WATER))
            assertEquals(NOT_COVERED, classify(MICRO_SIZE, CHEMICALS))
        }

        @Test
        fun `qualified trust services, TLD registries and DNS providers are essential at any size`() {
            for (type in listOf(QUALIFIED_TRUST_SERVICE_PROVIDER, TLD_NAME_REGISTRY, DNS_SERVICE_PROVIDER)) {
                assertEquals(ESSENTIAL, classify(MICRO_SIZE, type), type.name)
            }
        }

        @Test
        fun `electronic communications are essential from medium size and important below`() {
            assertEquals(ESSENTIAL, classify(MEDIUM_SIZE, PUBLIC_ELECTRONIC_COMMUNICATIONS_SERVICE))
            assertEquals(ESSENTIAL, classify(LARGE_SIZE, PUBLIC_ELECTRONIC_COMMUNICATIONS_SERVICE))
            assertEquals(IMPORTANT, classify(MICRO_SIZE, PUBLIC_ELECTRONIC_COMMUNICATIONS_SERVICE))
        }

        @Test
        fun `non-qualified trust services are covered at any size and essential only when large`() {
            assertEquals(IMPORTANT, classify(MICRO_SIZE, TRUST_SERVICE_PROVIDER))
            assertEquals(IMPORTANT, classify(MEDIUM_SIZE, TRUST_SERVICE_PROVIDER))
            assertEquals(ESSENTIAL, classify(LARGE_SIZE, TRUST_SERVICE_PROVIDER))
        }

        @Test
        fun `with several types the most demanding classification wins`() {
            assertEquals(ESSENTIAL, classify(LARGE_SIZE, CHEMICALS, HEALTHCARE_PROVIDER))
            assertEquals(IMPORTANT, classify(SMALL_SIZE, CHEMICALS, TRUST_SERVICE_PROVIDER))
        }

        @Test
        fun `a critical entity is essential at any size, even without an annex type`() {
            val result = assess(MICRO_SIZE, Nis2Answers(criticalEntity = true))
            assertEquals(ESSENTIAL, result.nis2Classification)
            assertTrue("nis2.critical-entity" in result.findingIds(ApplicabilityRules.NIS2))
        }

        @Test
        fun `significant impact lets the CNCS cover a small entity`() {
            val result = assess(SMALL_SIZE, Nis2Answers(entityTypes = setOf(CHEMICALS), significantImpact = true))
            assertEquals(NOT_COVERED, result.nis2Classification)
            assertTrue(result.cncsMayQualify)
            assertEquals(Applies.REVIEW, result.framework(ApplicabilityRules.NIS2).applies)
        }

        /** Art. 3.º n.º 2 needs an annex type, so the consultant checks whether the activity is in the annexes. */
        @Test
        fun `significant impact without an annex type is sent to the consultant`() {
            val result = assess(SMALL_SIZE, Nis2Answers(entityTypes = emptySet(), significantImpact = true))
            val nis2 = result.framework(ApplicabilityRules.NIS2)

            assertEquals(NOT_COVERED, result.nis2Classification)
            assertFalse(result.cncsMayQualify)
            assertEquals(Applies.REVIEW, nis2.applies)
            assertTrue(nis2.findings.single { it.id == "nis2.check-annex-type" }.review)
        }

        @Test
        fun `without significant impact or an annex type NIS2 does not apply`() {
            val result = assess(SMALL_SIZE, Nis2Answers())
            assertEquals(Applies.NO, result.framework(ApplicabilityRules.NIS2).applies)
            assertFalse("nis2.check-annex-type" in result.findingIds(ApplicabilityRules.NIS2))
        }

        @Test
        fun `significant impact is not flagged once essential`() {
            val result = assess(
                LARGE_SIZE, Nis2Answers(entityTypes = setOf(HEALTHCARE_PROVIDER), significantImpact = true),
            )
            assertFalse(result.cncsMayQualify)
        }

        @Test
        fun `public bodies and higher education are left to the consultant`() {
            for (result in listOf(assess(publicBody = true), assess(nis2 = Nis2Answers(higherEducation = true)))) {
                assertEquals(TO_BE_ASSESSED, result.nis2Classification)
                assertEquals(Applies.REVIEW, result.framework(ApplicabilityRules.NIS2).applies)
            }
        }

        @Test
        fun `every result explains the size and cites the law`() {
            val findings = assess(LARGE_SIZE, Nis2Answers(entityTypes = setOf(HEALTHCARE_PROVIDER)))
                .framework(ApplicabilityRules.NIS2).findings
            assertEquals(listOf("nis2.size.large", "nis2.essential.large-annex-i"), findings.map { it.id })
            assertTrue(findings.all { it.legalBasis.startsWith("RJC") && it.explanation.isNotBlank() })
        }
    }

    @Nested
    inner class Gdpr {

        @Test
        fun `does not apply without personal data`() {
            val result = assess(gdpr = GdprAnswers(processesPersonalData = false))
            assertEquals(Applies.NO, result.framework(ApplicabilityRules.GDPR).applies)
        }

        @Test
        fun `small organization with occasional processing is exempt from the records`() {
            val ids = assess(SMALL_SIZE).findingIds(ApplicabilityRules.GDPR)
            assertEquals(listOf("gdpr.applies", "gdpr.records-exempt", "gdpr.dpo-not-required"), ids)
        }

        @Test
        fun `records are required from 250 employees, for regular processing or for special data`() {
            val required = listOf(
                assess(SizeData(250, 1 * M)),
                assess(gdpr = GdprAnswers(processesPersonalData = true, regularOrRiskyProcessing = true)),
                assess(gdpr = GdprAnswers(processesPersonalData = true, specialOrCriminalData = true)),
            )
            for (result in required) {
                assertTrue("gdpr.records-required" in result.findingIds(ApplicabilityRules.GDPR))
            }
            assertTrue("gdpr.records-exempt" in assess(SizeData(249, 1 * M)).findingIds(ApplicabilityRules.GDPR))
        }

        @Test
        fun `details about personal data without processing personal data are rejected`() {
            val contradictory = listOf(
                { GdprAnswers(processesPersonalData = false, regularOrRiskyProcessing = true) },
                { GdprAnswers(processesPersonalData = false, specialOrCriminalData = true) },
                { GdprAnswers(processesPersonalData = false, largeScaleMonitoring = true) },
                { GdprAnswers(processesPersonalData = false, largeScaleSpecialData = true) },
            )
            for (answers in contradictory) {
                assertThrows<IllegalArgumentException> { answers() }
            }
        }

        @Test
        fun `a DPO is required for public bodies and large-scale monitoring or special data`() {
            val required = listOf(
                assess(publicBody = true),
                assess(gdpr = GdprAnswers(processesPersonalData = true, largeScaleMonitoring = true)),
                assess(gdpr = GdprAnswers(processesPersonalData = true, largeScaleSpecialData = true)),
            )
            for (result in required) {
                assertTrue("gdpr.dpo-required" in result.findingIds(ApplicabilityRules.GDPR))
            }
        }
    }

    @Nested
    inner class AiAct {

        @Test
        fun `does not apply without AI`() {
            assertEquals(Applies.NO, assess().framework(ApplicabilityRules.AI_ACT).applies)
        }

        @Test
        fun `details about AI without using or providing AI are rejected`() {
            assertThrows<IllegalArgumentException> { AiActAnswers(highRiskArea = true) }
            assertThrows<IllegalArgumentException> { AiActAnswers(interactsOrGeneratesContent = true) }
        }

        @Test
        fun `a company using AI tools is a deployer with the AI literacy duty`() {
            val result = assess(aiAct = AiActAnswers(usesAiSystems = true))
            assertEquals(Applies.YES, result.framework(ApplicabilityRules.AI_ACT).applies)
            assertEquals(listOf("ai-act.deployer", "ai-act.ai-literacy"), result.findingIds(ApplicabilityRules.AI_ACT))
        }

        @Test
        fun `a company selling its own AI is a provider`() {
            val ids = assess(aiAct = AiActAnswers(providesAiSystems = true)).findingIds(ApplicabilityRules.AI_ACT)
            assertEquals(listOf("ai-act.provider", "ai-act.ai-literacy"), ids)
        }

        @Test
        fun `AI in an annex III area is flagged for the consultant, not decided`() {
            val framework = assess(aiAct = AiActAnswers(usesAiSystems = true, highRiskArea = true))
                .framework(ApplicabilityRules.AI_ACT)
            val highRisk = framework.findings.single { it.id == "ai-act.possible-high-risk" }
            assertTrue(highRisk.review)
            assertEquals(Applies.YES, framework.applies)
        }

        @Test
        fun `chatbots and generated content may bring transparency duties, confirmed by the consultant`() {
            val transparency = assess(aiAct = AiActAnswers(usesAiSystems = true, interactsOrGeneratesContent = true))
                .framework(ApplicabilityRules.AI_ACT).findings.single { it.id == "ai-act.transparency" }
            assertTrue(transparency.review)
        }
    }

    @Test
    fun `the result is indicative, versioned and lists the three frameworks in order`() {
        val result = assess()
        assertTrue(result.indicative)
        assertEquals(ApplicabilityRules.VERSION, result.rulesVersion)
        assertEquals(listOf("nis2-qnrcs", "gdpr", "ai-act"), result.frameworks.map { it.framework })
    }

    @Test
    fun `the same input always gives the same result`() {
        val input = ApplicabilityInput(
            LARGE_SIZE, false, Nis2Answers(entityTypes = setOf(CHEMICALS, HEALTHCARE_PROVIDER)),
            GdprAnswers(processesPersonalData = true), AiActAnswers(usesAiSystems = true),
        )
        assertEquals(rules.assess(input), rules.assess(input))
    }

    @Nested
    inner class CaeSuggestion {

        @Test
        fun `NACE divisions 26 to 30 suggest the annex II manufacturing types`() {
            assertEquals(setOf(Nis2EntityType.ELECTRONICS_MANUFACTURING), Nis2EntityType.suggestedFor("26110"))
            assertEquals(setOf(Nis2EntityType.MOTOR_VEHICLE_MANUFACTURING), Nis2EntityType.suggestedFor(" 29100 "))
        }

        @Test
        fun `other CAE codes suggest nothing, so the user picks the type`() {
            assertTrue(Nis2EntityType.suggestedFor("62010").isEmpty())
            assertTrue(Nis2EntityType.suggestedFor("").isEmpty())
        }
    }

    @Test
    fun `every annex type has a reference and a label`() {
        for (type in Nis2EntityType.entries) {
            assertTrue(type.reference.startsWith("Anexo ${type.annex.name},"), type.name)
            assertTrue(type.label.isNotBlank(), type.name)
        }
    }
}
