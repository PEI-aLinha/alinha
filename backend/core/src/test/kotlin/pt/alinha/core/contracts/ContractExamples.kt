package pt.alinha.core.contracts

import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The shared payloads in contracts/<area>/examples/<contract>/{valid,invalid}/ (see contracts/README.md). The Python
 * tests load the same files, so both sides of a contract are tested against one set of examples.
 *
 * @property expectedErrors invalid examples only: the violations it must cause, as `location: keyword` (plus the
 * property name for `required`), from the contract folder's expected-errors.json. Pins *why* it is refused.
 */
data class ContractExample(val contract: String, val valid: Boolean, val file: Path, val expectedErrors: Set<String>) {
    val name get() = "$contract ${if (valid) "valid" else "invalid"}/${file.name}"

    fun document(json: JsonMapper): Map<String, Any?> = json.readValue(file.readText())
}

object ContractExamples {
    const val EXPECTED_ERRORS = "expected-errors.json"

    /** The repo's contracts/ folder, set by core's build.gradle.kts. */
    val root: Path = Path.of(checkNotNull(System.getProperty("alinha.contracts-dir")) { "alinha.contracts-dir is not set" })

    private val json = JsonMapper.builder().build()

    /** Every example under contracts/<area>/examples, grouped by contract (the folder under examples/). */
    fun of(area: String): List<ContractExample> {
        val examples = root.resolve(area).resolve("examples")
        require(examples.isDirectory()) { "No examples folder $examples" }
        val files = Files.walk(examples).use { paths ->
            paths.filter { it.extension == "json" && it.name != EXPECTED_ERRORS }.sorted().toList()
        }
        require(files.isNotEmpty()) { "No examples in $examples" }
        return files.groupBy { it.parent.parent }.flatMap { (contractDir, contractFiles) -> load(examples, contractDir, contractFiles) }
            .sortedBy { it.name }
    }

    private fun load(examples: Path, contractDir: Path, files: List<Path>): List<ContractExample> {
        val contract = examples.relativize(contractDir).toString()
        val invalid = files.filter { it.parent.name == "invalid" }.map { it.name }.toSet()
        val expected = expectedErrors(contractDir)
        require(expected.keys == invalid) {
            "$contract/$EXPECTED_ERRORS must list exactly the invalid examples: ${expected.keys} vs $invalid"
        }
        return files.map { file ->
            val kind = file.parent.name
            require(kind == "valid" || kind == "invalid") { "$file is not under valid/ or invalid/" }
            ContractExample(contract, kind == "valid", file, expected[file.name].orEmpty())
        }
    }

    private fun expectedErrors(contractDir: Path): Map<String, Set<String>> {
        val file = contractDir.resolve(EXPECTED_ERRORS)
        if (!Files.exists(file)) return emptyMap()
        return json.readValue<Map<String, List<String>>>(file.readText()).mapValues { it.value.toSet() }
    }
}

/** The violations in a validator's message (`<schema>: v1; v2`), for comparison with [ContractExample.expectedErrors]. */
fun violations(schema: String, message: String?): Set<String> =
    message.orEmpty().removePrefix("$schema: ").split("; ").toSet()
