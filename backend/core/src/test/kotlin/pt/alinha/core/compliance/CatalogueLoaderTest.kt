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
        assertThat(control.level).isEqualTo(ComplianceLevel.BASIC)
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
            .hasMessageContaining("colour")
    }

    @Test
    fun `ignores folders without a catalogue`() {
        frameworksDir.resolve("soc-cmm").createDirectories()

        assertThat(CatalogueLoader(frameworksDir).loadAll()).isEmpty()
    }

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
