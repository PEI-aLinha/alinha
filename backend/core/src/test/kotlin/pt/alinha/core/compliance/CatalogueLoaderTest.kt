package pt.alinha.core.compliance

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CatalogueLoaderTest {

    @TempDir
    lateinit var frameworksDir: Path

    @Test
    fun `loads the real catalogues in the repo`() {
        // Tests run from backend/core, so the repo's frameworks folder is two levels up.
        val catalogues = CatalogueLoader(Path.of("../../frameworks")).loadAll()

        assertThat(catalogues).anyMatch { it.framework == "nis2-qnrcs" && it.version == 1 }
    }

    @Test
    fun `reads requirements, controls and questions`() {
        write("nis2-qnrcs", "v1", validCatalogue())

        val catalogue = CatalogueLoader(frameworksDir).loadAll().single()

        assertThat(catalogue.requirements.single().controls).containsExactly("CTRL-MFA")
        val control = catalogue.controls.single()
        assertThat(control.level).isEqualTo("basic")
        assertThat(control.technical).isTrue()
        assertThat(control.questions.single().answers)
            .containsExactly(Answer.YES, Answer.PARTIAL, Answer.NO, Answer.UNKNOWN)
    }

    @Test
    fun `rejects a requirement that points to an unknown control`() {
        write("nis2-qnrcs", "v1", validCatalogue().replace("controls: [CTRL-MFA]", "controls: [CTRL-MISSING]"))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("unknown control 'CTRL-MISSING'")
    }

    @Test
    fun `rejects a version that does not match its folder`() {
        write("nis2-qnrcs", "v2", validCatalogue())

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("does not match folder 'v2'")
    }

    @Test
    fun `rejects a framework that does not match its folder`() {
        write("gdpr", "v1", validCatalogue())

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("does not match folder 'gdpr'")
    }

    @Test
    fun `rejects duplicate ids`() {
        write("nis2-qnrcs", "v1", validCatalogue().replace("id: CTRL-MFA-Q1", "id: CTRL-MFA"))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("duplicate id 'CTRL-MFA'")
    }

    @Test
    fun `rejects unknown fields`() {
        write("nis2-qnrcs", "v1", validCatalogue().replace("technical: true", "technical: true\n    colour: blue"))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("colour")
    }

    @Test
    fun `rejects a missing required field and names the file`() {
        write("nis2-qnrcs", "v1", validCatalogue().replace("title: \"NIS2\"\n", ""))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("catalogue.yaml")
            .hasMessageContaining("title")
    }

    @Test
    fun `rejects malformed YAML and names the file`() {
        write("nis2-qnrcs", "v1", "framework: [unclosed")

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("catalogue.yaml")
    }

    @Test
    fun `ignores folders without a catalogue`() {
        frameworksDir.resolve("soc-cmm").createDirectories()

        assertThat(CatalogueLoader(frameworksDir).loadAll()).isEmpty()
    }

    @Test
    fun `rejects a version folder without a catalogue file`() {
        frameworksDir.resolve("nis2-qnrcs").resolve("v2").createDirectories()

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("version folder has no catalogue.yaml")
    }

    @Test
    fun `rejects a folder that is not named like a version`() {
        write("nis2-qnrcs", "V2", validCatalogue())

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("'V2' is not a version folder")
    }

    @Test
    fun `rejects a version number that does not fit in an Int`() {
        write("nis2-qnrcs", "v99999999999", validCatalogue())

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("is not a version folder")
    }

    @Test
    fun `names the absolute path when the frameworks folder is missing`() {
        val missing = frameworksDir.resolve("missing")

        assertThatThrownBy { CatalogueLoader(missing).loadAll() }
            .hasMessageContaining(missing.toAbsolutePath().toString())
    }

    @Test
    fun `rejects empty evidence`() {
        write("nis2-qnrcs", "v1", validCatalogue().replace(
            "    evidence:\n      - \"Configuração\"",
            "    evidence: []",
        ))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("evidence must not be empty")
    }

    @Test
    fun `rejects answers with fewer than two items`() {
        write("nis2-qnrcs", "v1", validCatalogue().replace(
            "answers: [\"yes\", \"partial\", \"no\", \"unknown\"]",
            "answers: [\"yes\"]",
        ))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("at least 2 answers")
    }

    @Test
    fun `rejects an id that does not match the id pattern`() {
        write("nis2-qnrcs", "v1", validCatalogue().replace("id: RJC-27-1-i", "id: invalid-id"))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("does not match id pattern")
    }

    @Test
    fun `rejects blank text in a question`() {
        write("nis2-qnrcs", "v1", validCatalogue().replace(
            "text: \"Pedem um segundo fator?\"",
            "text: \"\"",
        ))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("text must not be blank")
    }

    @Test
    fun `a v1 catalogue without levels has the QNRCS compliance levels`() {
        write("nis2-qnrcs", "v1", validCatalogue())

        val catalogue = CatalogueLoader(frameworksDir).loadAll().single()

        assertThat(catalogue.levels).containsExactly(
            Level("basic", "Básico"), Level("substantial", "Substancial"),
            Level("high", "Elevado", EvidenceStrength.DOCUMENT_VERIFIED, EvidenceStrength.TECHNICALLY_VERIFIED),
        )
    }

    @Test
    fun `a v2 catalogue declares its own ordered level scale`() {
        write("soc-cmm", "v1", v2Catalogue())

        val catalogue = CatalogueLoader(frameworksDir).loadAll().single()

        assertThat(catalogue.levels.map { it.id }).containsExactly("1", "2", "3")
        assertThat(catalogue.levels.first().label).isEqualTo("Inicial")
        assertThat(catalogue.controls.single().level).isEqualTo("2")
    }

    @Test
    fun `rejects a control whose level is not in the catalogue's scale`() {
        write("soc-cmm", "v1", v2Catalogue().replace("level: \"2\"", "level: high"))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("control 'BUS.1' level 'high' is not in the catalogue's levels")
    }

    @Test
    fun `rejects a v1 control level outside the QNRCS levels`() {
        write("nis2-qnrcs", "v1", validCatalogue().replace("level: basic", "level: \"3\""))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("level '3' is not in the catalogue's levels")
    }

    @Test
    fun `rejects duplicate, malformed or unlabelled levels`() {
        write("soc-cmm", "v1", v2Catalogue().replace("id: \"3\", label: \"Definido\"", "id: \"1\", label: \" \""))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("duplicate level '1'")
            .hasMessageContaining("level '1' label must not be blank")
    }

    @Test
    fun `rejects an empty level scale`() {
        write("soc-cmm", "v1", v2Catalogue().replace(Regex("levels:\\n(  - .*\\n)+"), "levels: []\n"))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("levels must not be empty")
    }

    @Test
    fun `a v2 level declares its evidence bar, which defaults to self-reported`() {
        write("soc-cmm", "v1", v2Catalogue().replace(
            "  - { id: \"3\", label: \"Definido\" }",
            "  - { id: \"3\", label: \"Definido\", requiredStrength: DOCUMENT_VERIFIED }",
        ))

        val levels = CatalogueLoader(frameworksDir).loadAll().single().levels

        assertThat(levels[0].requiredStrength).isEqualTo(EvidenceStrength.SELF_REPORTED)
        assertThat(levels[0].requiredStrengthTechnical).isEqualTo(EvidenceStrength.SELF_REPORTED)
        assertThat(levels[2].requiredStrength).isEqualTo(EvidenceStrength.DOCUMENT_VERIFIED)
        // The technical bar is the same as the plain one unless declared.
        assertThat(levels[2].requiredStrengthTechnical).isEqualTo(EvidenceStrength.DOCUMENT_VERIFIED)
    }

    @Test
    fun `rejects a technical bar weaker than the plain one`() {
        write("soc-cmm", "v1", v2Catalogue().replace(
            "  - { id: \"3\", label: \"Definido\" }",
            "  - { id: \"3\", label: \"Definido\", requiredStrength: DOCUMENT_VERIFIED, requiredStrengthTechnical: SELF_REPORTED }",
        ))

        assertThatThrownBy { CatalogueLoader(frameworksDir).loadAll() }
            .isInstanceOf(InvalidCatalogueException::class.java)
            .hasMessageContaining("level '3' requiredStrengthTechnical must not be weaker than requiredStrength")
    }

    @Test
    fun `the real soc-cmm catalogue asks for verified evidence at maturity 4 and 5`() {
        val socCmm = CatalogueLoader(Path.of("../../frameworks")).loadAll().single { it.framework == "soc-cmm" }

        assertThat(socCmm.levels.map { it.id to it.requiredStrength }).containsExactly(
            "1" to EvidenceStrength.SELF_REPORTED, "2" to EvidenceStrength.SELF_REPORTED,
            "3" to EvidenceStrength.SELF_REPORTED, "4" to EvidenceStrength.DOCUMENT_VERIFIED,
            "5" to EvidenceStrength.DOCUMENT_VERIFIED,
        )
        assertThat(socCmm.levels.last().requiredStrengthTechnical).isEqualTo(EvidenceStrength.TECHNICALLY_VERIFIED)
    }

    private fun v2Catalogue() = """
        framework: soc-cmm
        version: 1
        title: "SOC-CMM"
        sources:
          - "SOC-CMM v3"
        levels:
          - { id: "1", label: "Inicial" }
          - { id: "2", label: "Gerido" }
          - { id: "3", label: "Definido" }
        requirements:
          - id: SOC-BUS
            reference: "SOC-CMM v3, Business"
            text: "Negócio"
            controls: [BUS.1]
        controls:
          - id: BUS.1
            title: "Missão do SOC"
            level: "2"
            technical: false
            evidence:
              - "Documento"
            questions:
              - id: BUS.1-Q1
                text: "Tem missão?"
                explanation: "Explicação."
                example: "Exemplo."
                answers: ["yes", "partial", "no", "unknown"]
    """.trimIndent()

    private fun write(framework: String, version: String, content: String) {
        val dir = frameworksDir.resolve(framework).resolve(version).createDirectories()
        dir.resolve(CatalogueLoader.CATALOGUE_FILE).writeText(content)
    }

    private fun validCatalogue() = """
        framework: nis2-qnrcs
        version: 1
        title: "NIS2"
        sources:
          - "Decreto-Lei n.º 125/2025"
        requirements:
          - id: RJC-27-1-i
            reference: "DL 125/2025, art. 27.º, n.º 1, al. i)"
            text: "Autenticação multifator"
            controls: [CTRL-MFA]
        controls:
          - id: CTRL-MFA
            title: "Autenticação com dois fatores"
            level: basic
            technical: true
            evidence:
              - "Configuração"
            questions:
              - id: CTRL-MFA-Q1
                text: "Pedem um segundo fator?"
                explanation: "Explicação."
                example: "Exemplo."
                answers: ["yes", "partial", "no", "unknown"]
    """.trimIndent()
}
