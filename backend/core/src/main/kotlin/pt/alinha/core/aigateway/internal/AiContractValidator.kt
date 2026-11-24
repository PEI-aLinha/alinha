package pt.alinha.core.aigateway.internal

import com.networknt.schema.Error
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * Validates AI Gateway ↔ AI Service bodies against `components.schemas` of contracts/ai/ai-service.v1.yaml (shipped
 * in the jar by core's processResources). The gateway checks each request before sending it and each response before
 * trusting it (#33): the AI Service is a separate process, so its output is untrusted until it matches the contract.
 */
@Component
class AiContractValidator(private val json: JsonMapper) {
    // 2020-12 treats "format" as an annotation by default; date-time must be enforced (generatedAt).
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
        it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
    }
    private val document: JsonNode by lazy { load() }
    private val schemas = ConcurrentHashMap<String, Schema>()

    /**
     * @param schemaName a `components.schemas` name, e.g. `AiDraft`.
     * @throws IllegalArgumentException listing each violation as `location: keyword`. Never the library's message: it
     * can quote the body, and AI context or drafts must not reach the logs.
     */
    fun validate(schemaName: String, body: Map<String, Any?>) {
        val tree = try {
            json.valueToTree<JsonNode>(body)
        } catch (e: JacksonException) {
            throw IllegalArgumentException("$schemaName: body is not JSON (${e.javaClass.simpleName})")
        }
        val errors = schema(schemaName).validate(tree)
        require(errors.isEmpty()) { "$schemaName: ${errors.joinToString("; ", transform = ::describe)}" }
    }

    /**
     * The whole contract with a root `$ref` to the named schema, so `#/components/schemas/...` references inside it
     * keep resolving. The OpenAPI keys around it (paths, info, ...) are not JSON Schema keywords and are ignored.
     */
    private fun schema(name: String): Schema = schemas.computeIfAbsent(name) {
        require(document.at("/components/schemas/$name").isObject) { "No schema $name in $CONTRACT" }
        val root = (document.deepCopy() as ObjectNode).apply {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("\$ref", "#/components/schemas/$name")
        }
        registry.getSchema(SchemaLocation.of("$BASE_URI#"), root)
    }

    private fun load(): JsonNode {
        val resource = ClassPathResource(CONTRACT)
        require(resource.exists()) { "No AI contract $CONTRACT" }
        return resource.inputStream.use { YAMLMapper.builder().build().readTree(it) }
    }

    /** A `required` property name comes from the schema, so it is safe to show; any other name may come from the body. */
    private fun describe(error: Error): String {
        val location = error.instanceLocation.toString().ifEmpty { "/" }
        val missing = if (error.keyword == REQUIRED) " ${error.property}" else ""
        return "$location: ${error.keyword}$missing"
    }

    private companion object {
        const val CONTRACT = "contracts/ai/ai-service.v1.yaml"
        const val BASE_URI = "https://alinha.pt/contracts/ai/ai-service.v1.yaml"
        const val REQUIRED = "required"
    }
}
