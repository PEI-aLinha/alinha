package pt.alinha.core.compliance

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import tools.jackson.databind.DeserializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

/**
 * Reads framework catalogues from a folder on disk, so a new version needs no recompile or
 * redeploy (NFR18). Expected layout: <frameworksDir>/<framework>/v<n>/catalogue.yaml
 */
class CatalogueLoader(private val frameworksDir: Path) {

    private val mapper = YAMLMapper.builder()
        .addModule(kotlinModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** Loads every catalogue found under the frameworks folder. */
    fun loadAll(): List<Catalogue> {
        require(frameworksDir.isDirectory()) { "Frameworks folder not found: $frameworksDir" }

        return frameworksDir.listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedBy { it.name }
            .flatMap { frameworkDir ->
                frameworkDir.listDirectoryEntries("v*")
                    .filter { it.isDirectory() && it.resolve(CATALOGUE_FILE).exists() }
                    .sortedBy { it.name.removePrefix("v").toIntOrNull() ?: 0 }
                    .map { versionDir -> load(versionDir.resolve(CATALOGUE_FILE)) }
            }
    }

    /** Loads and checks one catalogue file. */
    fun load(file: Path): Catalogue {
        val catalogue: Catalogue = file.inputStream().use { mapper.readValue(it) }
        val problems = checkConsistency(catalogue, file)
        if (problems.isNotEmpty()) throw InvalidCatalogueException(file, problems)
        return catalogue
    }

    /** Rules the JSON Schema cannot express. */
    private fun checkConsistency(catalogue: Catalogue, file: Path): List<String> {
        val problems = mutableListOf<String>()

        val versionDir = file.parent
        val frameworkDir = versionDir.parent
        if (catalogue.framework != frameworkDir.name) {
            problems += "framework '${catalogue.framework}' does not match folder '${frameworkDir.name}'"
        }
        if ("v${catalogue.version}" != versionDir.name) {
            problems += "version ${catalogue.version} does not match folder '${versionDir.name}'"
        }

        val ids = catalogue.requirements.map { it.id } +
                catalogue.controls.map { it.id } +
                catalogue.controls.flatMap { control -> control.questions.map { it.id } }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            problems += "duplicate id '$it'"
        }

        val controlIds = catalogue.controls.map { it.id }.toSet()
        catalogue.requirements.forEach { requirement ->
            requirement.controls.filterNot { it in controlIds }.forEach {
                problems += "requirement '${requirement.id}' points to unknown control '$it'"
            }
        }

        return problems
    }

    companion object {
        const val CATALOGUE_FILE = "catalogue.yaml"
    }
}

class InvalidCatalogueException(file: Path, problems: List<String>) :
    RuntimeException("Invalid catalogue $file:\n" + problems.joinToString("\n") { "  - $it" })