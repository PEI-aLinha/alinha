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
    private fun load(file: Path): Catalogue {
        val catalogue: Catalogue = file.inputStream().use { mapper.readValue(it) }
        val problems = checkConsistency(catalogue, file)
        if (problems.isNotEmpty()) throw InvalidCatalogueException(file, problems)
        return catalogue
    }

    /** Rules the JSON Schema cannot express (applied at runtime in addition to CI validation). */
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

        // Schema constraints the Jackson binding cannot enforce
        if (!FRAMEWORK_PATTERN.matches(catalogue.framework)) {
            problems += "framework '${catalogue.framework}' does not match pattern ^[a-z0-9]+(-[a-z0-9]+)*\$"
        }
        if (catalogue.version < 1) {
            problems += "version must be >= 1, got ${catalogue.version}"
        }
        if (catalogue.title.isBlank()) {
            problems += "title must not be blank"
        }
        if (catalogue.sources.isEmpty()) {
            problems += "sources must not be empty"
        }
        catalogue.sources.forEachIndexed { i, s ->
            if (s.isBlank()) problems += "sources[$i] must not be blank"
        }
        if (catalogue.requirements.isEmpty()) {
            problems += "requirements must not be empty"
        }
        if (catalogue.controls.isEmpty()) {
            problems += "controls must not be empty"
        }

        catalogue.requirements.forEach { req ->
            if (!ID_PATTERN.matches(req.id)) {
                problems += "requirement id '${req.id}' does not match id pattern"
            }
            if (req.reference.isBlank()) {
                problems += "requirement '${req.id}' reference must not be blank"
            }
            if (req.text.isBlank()) {
                problems += "requirement '${req.id}' text must not be blank"
            }
            if (req.controls.isEmpty()) {
                problems += "requirement '${req.id}' controls must not be empty"
            }
            req.controls.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { dup ->
                problems += "requirement '${req.id}' has duplicate control reference '$dup'"
            }
        }

        catalogue.controls.forEach { ctrl ->
            if (!ID_PATTERN.matches(ctrl.id)) {
                problems += "control id '${ctrl.id}' does not match id pattern"
            }
            if (ctrl.title.isBlank()) {
                problems += "control '${ctrl.id}' title must not be blank"
            }
            if (ctrl.evidence.isEmpty()) {
                problems += "control '${ctrl.id}' evidence must not be empty"
            }
            ctrl.evidence.forEachIndexed { i, e ->
                if (e.isBlank()) problems += "control '${ctrl.id}' evidence[$i] must not be blank"
            }
            if (ctrl.questions.isEmpty()) {
                problems += "control '${ctrl.id}' questions must not be empty"
            }
            ctrl.questions.forEach { q ->
                if (!ID_PATTERN.matches(q.id)) {
                    problems += "question id '${q.id}' does not match id pattern"
                }
                if (q.text.isBlank()) {
                    problems += "question '${q.id}' text must not be blank"
                }
                if (q.explanation.isBlank()) {
                    problems += "question '${q.id}' explanation must not be blank"
                }
                if (q.example.isBlank()) {
                    problems += "question '${q.id}' example must not be blank"
                }
                if (q.answers.size < 2) {
                    problems += "question '${q.id}' must have at least 2 answers, got ${q.answers.size}"
                }
                q.answers.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { dup ->
                    problems += "question '${q.id}' has duplicate answer '$dup'"
                }
            }
        }

        // Global duplicate id check (requirements, controls and questions share a namespace)
        val ids = catalogue.requirements.map { it.id } +
                catalogue.controls.map { it.id } +
                catalogue.controls.flatMap { control -> control.questions.map { it.id } }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            problems += "duplicate id '$it'"
        }

        // Cross-reference check
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
        private val FRAMEWORK_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        private val ID_PATTERN = Regex("^[A-Z0-9]+([.-][A-Za-z0-9]+)*$")
    }
}

class InvalidCatalogueException(file: Path, problems: List<String>) :
    RuntimeException("Invalid catalogue $file:\n" + problems.joinToString("\n") { "  - $it" })
