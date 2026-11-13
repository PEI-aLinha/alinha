package pt.alinha.core.compliance

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationEventPublisher

class CatalogueRegistryTest {

    @TempDir
    lateinit var frameworksDir: Path

    private val published = mutableListOf<Any>()
    private val events = ApplicationEventPublisher { published += it }

    @Test
    fun `announces every version on startup`() {
        write("nis2-qnrcs", 1)
        write("nis2-qnrcs", 2)

        registry().onStartup()

        assertThat(published).containsExactly(
            CatalogueVersionPublished("nis2-qnrcs", 1, "NIS2 v1", (published[0] as CatalogueVersionPublished).occurredAt),
            CatalogueVersionPublished("nis2-qnrcs", 2, "NIS2 v2", (published[1] as CatalogueVersionPublished).occurredAt),
        )
    }

    @Test
    fun `keeps old versions so existing assessments stay pinned`() {
        write("nis2-qnrcs", 1)
        write("nis2-qnrcs", 2)
        val registry = registry().also { it.onStartup() }

        assertThat(registry.get("nis2-qnrcs", 1)?.version).isEqualTo(1)
        assertThat(registry.latest("nis2-qnrcs")?.version).isEqualTo(2)
        assertThat(registry.get("nis2-qnrcs", 3)).isNull()
    }

    @Test
    fun `a reload only announces new versions`() {
        write("nis2-qnrcs", 1)
        val registry = registry().also { it.onStartup() }
        published.clear()

        write("nis2-qnrcs", 2)
        registry.reload()

        assertThat(published).hasSize(1)
        assertThat((published.single() as CatalogueVersionPublished).version).isEqualTo(2)
    }

    @Test
    fun `finds every framework that uses a canonical control`() {
        write("nis2-qnrcs", 1)
        write("gdpr", 1)
        val registry = registry().also { it.onStartup() }

        assertThat(registry.frameworksWithControl("CTRL-MFA")).containsExactly("gdpr", "nis2-qnrcs")
        assertThat(registry.frameworksWithControl("CTRL-UNKNOWN")).isEmpty()
    }

    private fun registry() = CatalogueRegistry(frameworksDir.toString(), events)

    private fun write(framework: String, version: Int) {
        val dir = frameworksDir.resolve(framework).resolve("v$version").createDirectories()
        dir.resolve(CatalogueLoader.CATALOGUE_FILE).writeText(
            """
            framework: $framework
            version: $version
            title: "NIS2 v$version"
            sources:
              - "Fonte"
            requirements:
              - id: REQ-1
                reference: "Ref"
                text: "Texto"
                controls: [CTRL-MFA]
            controls:
              - id: CTRL-MFA
                title: "Autenticação com dois fatores"
                level: basic
                technical: true
                evidence: []
                questions:
                  - id: CTRL-MFA-Q1
                    text: "Pergunta"
                    explanation: "Explicação"
                    example: "Exemplo"
                    answers: ["yes", "no"]
            """.trimIndent(),
        )
    }
}