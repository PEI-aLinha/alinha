package pt.alinha.core.compliance

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule
import tools.jackson.module.kotlin.readValue

/**
 * Reads framework catalogues from a folder on disk, not from the jar, so a new version needs no
 * recompile or new build (RNF18). Expected layout: <frameworksDir>/<framework>/v<n>/catalogue.yaml
 *
 * Internal to the core: plugins read catalogues through [CatalogueRegistry].
 */
internal class CatalogueLoader(private val frameworksDir: Path) {

    private val mapper = YAMLMapper.builder()
        .addModule(kotlinModule())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** Loads every catalogue found under the frameworks folder. */
    fun loadAll(): List<Catalogue> {
        require(frameworksDir.isDirectory()) {
            "Frameworks folder not found: ${frameworksDir.toAbsolutePath().normalize()} (set FRAMEWORKS_DIR)"
        }

        return frameworksDir.listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedBy { it.name }
            .flatMap { frameworkDir ->
                frameworkDir.listDirectoryEntries()
                    .filter { it.isDirectory() }
                    .onEach(::checkVersionDir)
                    .sortedBy { it.name.removePrefix("v").toInt() }
                    .map { versionDir -> load(versionDir.resolve(CATALOGUE_FILE)) }
            }
    }

    /** A misnamed or empty version folder must stop the load, not be skipped in silence. */
    private fun checkVersionDir(dir: Path) {
        if (!VERSION_DIR_PATTERN.matches(dir.name)) {
            throw InvalidCatalogueException(dir, listOf("folder '${dir.name}' is not a version folder (expected v<n>)"))
        }
        if (!dir.resolve(CATALOGUE_FILE).exists()) {
            throw InvalidCatalogueException(dir, listOf("version folder has no $CATALOGUE_FILE"))
        }
    }

    /** Loads and checks one catalogue file. */
    private fun load(file: Path): Catalogue {
        val catalogue: Catalogue = try {
            file.inputStream().use { mapper.readValue(it) }
        } catch (e: JacksonException) {
            throw InvalidCatalogueException(file, listOf(e.message ?: "cannot be parsed"), e)
        } catch (e: IOException) {
            throw InvalidCatalogueException(file, listOf(e.message ?: "cannot be read"), e)
        }
        val problems = checkConsistency(catalogue, file)
        if (problems.isNotEmpty()) throw InvalidCatalogueException(file, problems)
        return catalogue
    }

    /** The schema rules, enforced again at runtime, plus the rules the JSON Schema cannot express. */
    private fun checkConsistency(catalogue: Catalogue, file: Path): List<String> =
        checkHeader(catalogue, file) +
            checkLevels(catalogue) +
            catalogue.requirements.flatMap(::checkRequirement) +
            catalogue.controls.flatMap { checkControl(it, catalogue) } +
            checkCrossReferences(catalogue)

    private fun checkHeader(catalogue: Catalogue, file: Path): List<String> {
        val problems = mutableListOf<String>()
        val versionDir = file.parent
        val frameworkDir = versionDir.parent
        if (catalogue.framework != frameworkDir.name) {
            problems += "framework '${catalogue.framework}' does not match folder '${frameworkDir.name}'"
        }
        if ("v${catalogue.version}" != versionDir.name) {
            problems += "version ${catalogue.version} does not match folder '${versionDir.name}'"
        }
        if (!FRAMEWORK_PATTERN.matches(catalogue.framework)) {
            problems += "framework '${catalogue.framework}' does not match pattern ^[a-z0-9]+(-[a-z0-9]+)*\$"
        }
        if (catalogue.version < 1) problems += "version must be >= 1, got ${catalogue.version}"
        if (catalogue.title.isBlank()) problems += "title must not be blank"
        if (catalogue.sources.isEmpty()) problems += "sources must not be empty"
        catalogue.sources.forEachIndexed { i, source ->
            if (source.isBlank()) problems += "sources[$i] must not be blank"
        }
        if (catalogue.requirements.isEmpty()) problems += "requirements must not be empty"
        if (catalogue.controls.isEmpty()) problems += "controls must not be empty"
        return problems
    }

    private fun checkLevels(catalogue: Catalogue): List<String> {
        val problems = mutableListOf<String>()
        if (catalogue.levels.isEmpty()) problems += "levels must not be empty"
        catalogue.levels.forEach { level ->
            if (!LEVEL_ID_PATTERN.matches(level.id)) problems += "level id '${level.id}' does not match level id pattern"
            if (level.label.isBlank()) problems += "level '${level.id}' label must not be blank"
        }
        duplicates(catalogue.levels.map { it.id }).forEach { problems += "duplicate level '$it'" }
        return problems
    }

    private fun checkRequirement(requirement: Requirement): List<String> {
        val problems = mutableListOf<String>()
        val id = requirement.id
        if (!ID_PATTERN.matches(id)) problems += "requirement id '$id' does not match id pattern"
        if (requirement.reference.isBlank()) problems += "requirement '$id' reference must not be blank"
        if (requirement.text.isBlank()) problems += "requirement '$id' text must not be blank"
        if (requirement.controls.isEmpty()) problems += "requirement '$id' controls must not be empty"
        duplicates(requirement.controls).forEach {
            problems += "requirement '$id' has duplicate control reference '$it'"
        }
        return problems
    }

    private fun checkControl(control: Control, catalogue: Catalogue): List<String> {
        val problems = mutableListOf<String>()
        val id = control.id
        if (!ID_PATTERN.matches(id)) problems += "control id '$id' does not match id pattern"
        if (catalogue.levelIndex(control.level) < 0) {
            problems += "control '$id' level '${control.level}' is not in the catalogue's levels"
        }
        if (control.title.isBlank()) problems += "control '$id' title must not be blank"
        if (control.evidence.isEmpty()) problems += "control '$id' evidence must not be empty"
        control.evidence.forEachIndexed { i, evidence ->
            if (evidence.isBlank()) problems += "control '$id' evidence[$i] must not be blank"
        }
        if (control.questions.isEmpty()) problems += "control '$id' questions must not be empty"
        return problems + control.questions.flatMap(::checkQuestion)
    }

    private fun checkQuestion(question: Question): List<String> {
        val problems = mutableListOf<String>()
        val id = question.id
        if (!ID_PATTERN.matches(id)) problems += "question id '$id' does not match id pattern"
        if (question.text.isBlank()) problems += "question '$id' text must not be blank"
        if (question.explanation.isBlank()) problems += "question '$id' explanation must not be blank"
        if (question.example.isBlank()) problems += "question '$id' example must not be blank"
        if (question.answers.size < 2) {
            problems += "question '$id' must have at least 2 answers, got ${question.answers.size}"
        }
        duplicates(question.answers).forEach { problems += "question '$id' has duplicate answer '$it'" }
        return problems
    }

    /** Requirements, controls and questions share one id namespace; requirements must point to known controls. */
    private fun checkCrossReferences(catalogue: Catalogue): List<String> {
        val ids = catalogue.requirements.map { it.id } +
            catalogue.controls.map { it.id } +
            catalogue.controls.flatMap { control -> control.questions.map { it.id } }
        val controlIds = catalogue.controls.map { it.id }.toSet()

        return duplicates(ids).map { "duplicate id '$it'" } +
            catalogue.requirements.flatMap { requirement ->
                requirement.controls.filterNot { it in controlIds }
                    .map { "requirement '${requirement.id}' points to unknown control '$it'" }
            }
    }

    private fun <T> duplicates(values: List<T>): Set<T> =
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    companion object {
        const val CATALOGUE_FILE = "catalogue.yaml"

        // At most 9 digits, so the number always fits in an Int.
        private val VERSION_DIR_PATTERN = Regex("v[1-9]\\d{0,8}")
        private val FRAMEWORK_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        private val ID_PATTERN = Regex("^[A-Z0-9]+([.-][A-Za-z0-9]+)*$")
        private val LEVEL_ID_PATTERN = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    }
}

internal class InvalidCatalogueException(file: Path, problems: List<String>, cause: Throwable? = null) :
    RuntimeException("Invalid catalogue $file:\n" + problems.joinToString("\n") { "  - $it" }, cause)
