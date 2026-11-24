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
 */
data class ContractExample(val contract: String, val valid: Boolean, val file: Path) {
    val name get() = "$contract ${if (valid) "valid" else "invalid"}/${file.name}"

    fun document(json: JsonMapper): Map<String, Any?> = json.readValue(file.readText())
}

object ContractExamples {
    /** The repo's contracts/ folder, set by core's build.gradle.kts. */
    val root: Path = Path.of(checkNotNull(System.getProperty("alinha.contracts-dir")) { "alinha.contracts-dir is not set" })

    /** Every example under contracts/<area>/examples, grouped by contract (the folder under examples/). */
    fun of(area: String): List<ContractExample> {
        val examples = root.resolve(area).resolve("examples")
        require(examples.isDirectory()) { "No examples folder $examples" }
        return Files.walk(examples).use { paths ->
            paths.filter { it.extension == "json" }.map { file ->
                val kind = file.parent
                require(kind.name == "valid" || kind.name == "invalid") { "$file is not under valid/ or invalid/" }
                ContractExample(examples.relativize(kind.parent).toString(), kind.name == "valid", file)
            }.sorted(compareBy { it.name }).toList()
        }.also { require(it.isNotEmpty()) { "No examples in $examples" } }
    }
}
