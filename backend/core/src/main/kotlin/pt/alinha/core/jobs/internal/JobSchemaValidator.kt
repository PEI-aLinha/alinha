package pt.alinha.core.jobs.internal

import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
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

    /** @throws IllegalArgumentException listing the violations (paths and rule messages, never instance values). */
    fun validate(schemaName: String, document: Map<String, Any?>) {
        val errors = schema(schemaName).validate(json.valueToTree<JsonNode>(document))
        require(errors.isEmpty()) { "$schemaName: ${errors.joinToString("; ")}" }
    }

    private fun schema(name: String): Schema = schemas.computeIfAbsent(name) {
        val resource = ClassPathResource("$CONTRACTS/$name.schema.json")
        require(resource.exists()) { "No job contract $CONTRACTS/$name.schema.json" }
        resource.inputStream.use(registry::getSchema)
    }

    companion object {
        private const val CONTRACTS = "contracts/jobs"

        /** evidence.collect v1 → evidence-collect.v1 */
        fun envelopeSchema(type: String, version: Int) = "${type.replace('.', '-')}.v$version"
    }
}
