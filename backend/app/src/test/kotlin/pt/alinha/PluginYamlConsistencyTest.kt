package pt.alinha

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.PluginManifest
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Every plugin's `plugin.yaml` says the same as its `AlinhaPlugin` bean (CLAUDE.md: "its PluginManifest
 * must match plugin.yaml"). Runs in the main repo's CI and in every plugin's CI, like [ModularityTest].
 */
class PluginYamlConsistencyTest {
    private val yaml = YAMLMapper.builder().addModule(kotlinModule()).build()

    @TestFactory
    fun `plugin yaml matches the Kotlin manifest`(): List<DynamicTest> {
        val beans = pluginBeans().associateBy { it.id }
        val files = pluginYamlFiles()
        assertThat(files).describedAs("plugin.yaml files under plugins/").isNotEmpty

        return files.map { file ->
            DynamicTest.dynamicTest(repoRoot().relativize(file).toString()) {
                val declared = yaml.readValue<YamlManifest>(Files.readString(file))
                val bean = requireNotNull(beans[declared.id]) { "No AlinhaPlugin bean with id '${declared.id}'" }
                assertThat(declared.comparable()).isEqualTo(bean.comparable())
            }
        } + DynamicTest.dynamicTest("every plugin bean has a plugin.yaml") {
            val yamlIds = files.map { yaml.readValue<YamlManifest>(Files.readString(it)).id }
            assertThat(yamlIds).containsExactlyInAnyOrderElementsOf(beans.keys)
        }
    }

    private fun pluginBeans(): List<PluginManifest> =
        ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AssignableTypeFilter(AlinhaPlugin::class.java)) }
            .findCandidateComponents("pt.alinha")
            .map { Class.forName(it.beanClassName).getDeclaredConstructor().newInstance() as AlinhaPlugin }
            .map { it.manifest }

    private fun pluginYamlFiles(): List<Path> =
        listOf("feature", "provider", "framework")
            .map { repoRoot().resolve("plugins").resolve(it) }
            .filter { it.isDirectory() }
            .flatMap { it.listDirectoryEntries() }
            .map { it.resolve("plugin.yaml") }
            .filter { it.exists() }
            .sorted()

    /** Walks up from the test's working directory to the folder holding `settings.gradle.kts`. */
    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { it.resolve("settings.gradle.kts").exists() }

    private fun PluginManifest.comparable() = Comparable(
        id, name, kind.name.lowercase(), version, publishes, consumes, permissions,
        nav.map { NavYaml(it.label, it.path, it.area.name.lowercase(), it.permission, it.order) },
    )

    private fun YamlManifest.comparable() = Comparable(
        id, name, kind.lowercase(), version, publishes, consumes, permissions, nav,
    )

    private data class Comparable(
        val id: String,
        val name: String,
        val kind: String,
        val version: String,
        val publishes: Set<String>,
        val consumes: Set<String>,
        val permissions: Set<String>,
        val nav: List<NavYaml>,
    )

    /** `plugin.yaml`; `path` is for tooling and is not part of the manifest. */
    private data class YamlManifest(
        val id: String,
        val name: String,
        val kind: String,
        val version: String,
        val path: String? = null,
        val publishes: Set<String> = emptySet(),
        val consumes: Set<String> = emptySet(),
        val permissions: Set<String> = emptySet(),
        val nav: List<NavYaml> = emptyList(),
    )

    private data class NavYaml(
        val label: String,
        val path: String,
        val area: String,
        val permission: String? = null,
        val order: Int = 0,
    )
}
