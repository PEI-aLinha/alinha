package pt.alinha

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.stereotype.Component
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.PluginManifest
import tools.jackson.databind.DeserializationFeature
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
 *
 * Compared: id, name, kind, version, publishes, consumes, permissions and nav. Not compared: `rolePermissions`,
 * which lives only in Kotlin (plugin.yaml says so), and `path`, which is for tooling.
 */
class PluginYamlConsistencyTest {
    // Jackson 3 ignores unknown keys by default; a typo such as `permisions:` must fail instead.
    private val yaml = YAMLMapper.builder()
        .addModule(kotlinModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    @TestFactory
    fun `plugin yaml matches the Kotlin manifest`(): List<DynamicTest> {
        val beanList = pluginBeans()
        val fileList = pluginYamlFiles().map { read(it).id to it }
        // Not associateBy: a second plugin.yaml or bean with the same id would be dropped, never compared.
        val beans = beanList.associateBy { it.id }
        val files = fileList.toMap()

        val unique = DynamicTest.dynamicTest("every plugin id is declared once") {
            val duplicateFiles = fileList.groupBy({ it.first }, { repoRoot().relativize(it.second).toString() })
                .filterValues { it.size > 1 }
            val duplicateBeans = beanList.groupBy { it.id }.filterValues { it.size > 1 }.keys
            assertThat(duplicateFiles).describedAs("plugin.yaml files sharing an id").isEmpty()
            assertThat(duplicateBeans).describedAs("AlinhaPlugin beans sharing an id").isEmpty()
        }
        val perFile = fileList.map { (id, file) ->
            DynamicTest.dynamicTest(repoRoot().relativize(file).toString()) {
                val bean = requireNotNull(beans[id]) { "$file declares id '$id' but no AlinhaPlugin bean has that id" }
                assertThat(read(file).comparable()).isEqualTo(bean.comparable())
            }
        }
        val perBean = beans.keys.map { id ->
            DynamicTest.dynamicTest("plugin '$id' has a plugin.yaml") {
                assertThat(files).describedAs(
                    "No plugin.yaml with id '$id' under plugins/. Is the submodule initialised " +
                        "(git submodule update --init --recursive)?",
                ).containsKey(id)
            }
        }
        return listOf(unique) + perFile + perBean
    }

    private fun read(file: Path): YamlManifest = yaml.readValue(Files.readString(file))

    /** Plugin beans: concrete `@Component` classes implementing [AlinhaPlugin] (test fixtures are not components). */
    private fun pluginBeans(): List<PluginManifest> =
        ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AssignableTypeFilter(AlinhaPlugin::class.java)) }
            .apply { addExcludeFilter { reader, _ -> !reader.annotationMetadata.isAnnotated(Component::class.java.name) } }
            .findCandidateComponents("pt.alinha")
            .map { instantiate(Class.forName(it.beanClassName)).manifest }

    private fun instantiate(type: Class<*>): AlinhaPlugin {
        val constructor = runCatching { type.getDeclaredConstructor() }.getOrElse {
            throw IllegalStateException(
                "${type.name} needs a no-argument constructor: the AlinhaPlugin bean only describes the plugin, " +
                    "inject dependencies elsewhere",
                it,
            )
        }
        return constructor.newInstance() as AlinhaPlugin
    }

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
        nav.map { NavYaml(it.label, it.path, it.area.name.lowercase(), it.resource, it.action, it.order) },
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

    /** `plugin.yaml`. Defaults match [PluginManifest] and `NavEntry`, so omitted keys mean the same in both. */
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
        val resource: String? = null,
        val action: String = "read",
        val order: Int = 100,
    )
}
