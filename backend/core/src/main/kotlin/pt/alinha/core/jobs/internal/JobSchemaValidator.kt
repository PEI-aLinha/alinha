package pt.alinha.core.jobs.internal

import com.networknt.schema.Error
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

/** Validates job documents against contracts/jobs (shipped in the jar by core's processResources). */
@Component
class JobSchemaValidator(private val json: JsonMapper) {
    // 2020-12 treats "format" as an annotation by default; the contracts rely on uuid/date-time being enforced.
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
        it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
    }
    private val schemas = ConcurrentHashMap<String, Schema>()

    /**
     * @throws IllegalArgumentException listing each violation as `location: keyword`. Never the library's message: it
     * can quote the payload (unknown property names, for one), and this text reaches logs and the dead-letter stream.
     * Also when [document] holds a value Jackson cannot turn into JSON.
     */
    fun validate(schemaName: String, document: Map<String, Any?>) {
        val tree = try {
            json.valueToTree<JsonNode>(document)
        } catch (e: JacksonException) {
            // Not e.message: it can name the payload's classes and values.
            throw IllegalArgumentException("$schemaName: document is not JSON (${e.javaClass.simpleName})")
        }
        val errors = schema(schemaName).validate(tree)
        require(errors.isEmpty()) { "$schemaName: ${errors.joinToString("; ", transform = ::describe)}" }
    }

    /**
     * [validate] against the contract of [type] v[version]. Dots become dashes in the file name, so `a-b.c` and
     * `a.b-c` share one file: the contract must also pin `properties.type.const` to [type], or no envelope matches.
     */
    fun validateEnvelope(type: String, version: Int, envelope: Map<String, Any?>) {
        val name = envelopeSchema(type, version)
        require(declaredTypes.computeIfAbsent(name, ::declaredType) == type) {
            "$CONTRACTS/$name.schema.json is not the contract of $type: its properties.type.const must be \"$type\""
        }
        validate(name, envelope)
    }

    private val declaredTypes = ConcurrentHashMap<String, String>()

    private fun declaredType(name: String): String =
        contract(name).inputStream.use { json.readTree(it) }.at("/properties/type/const").asString("")

    /** A `required` property name comes from the schema, so it is safe to show; any other name may come from the payload. */
    private fun describe(error: Error): String {
        val location = error.instanceLocation.toString().ifEmpty { "/" }
        val missing = if (error.keyword == REQUIRED) " ${error.property}" else ""
        return "$location: ${error.keyword}$missing"
    }

    private fun schema(name: String): Schema = schemas.computeIfAbsent(name) {
        contract(name).inputStream.use(registry::getSchema)
    }

    private fun contract(name: String) = ClassPathResource("$CONTRACTS/$name.schema.json").also {
        require(it.exists()) { "No job contract $CONTRACTS/$name.schema.json" }
    }

    companion object {
        private const val CONTRACTS = "contracts/jobs"
        private const val REQUIRED = "required"

        /** evidence.collect v1 → evidence-collect.v1 */
        fun envelopeSchema(type: String, version: Int) = "${type.replace('.', '-')}.v$version"
    }
}
