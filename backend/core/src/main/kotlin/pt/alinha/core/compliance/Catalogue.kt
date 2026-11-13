package pt.alinha.core.compliance

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A framework catalogue, read from frameworks/<framework>/v<n>/catalogue.yaml.
 * Format: contracts/frameworks/catalogue.v1.schema.json.
 */
data class Catalogue(
    val framework: String,
    val version: Int,
    val title: String,
    val sources: List<String>,
    val requirements: List<Requirement>,
    val controls: List<Control>,
)

/** What the law asks for, pointing to the canonical controls that satisfy it. */
data class Requirement(
    val id: String,
    val reference: String,
    val text: String,
    val controls: List<String>,
)

/** A canonical control: one concrete measure, possibly shared by several frameworks. */
data class Control(
    val id: String,
    val title: String,
    val level: ComplianceLevel,
    val technical: Boolean,
    val evidence: List<String>,
    val questions: List<Question>,
)

data class Question(
    val id: String,
    val text: String,
    val explanation: String,
    val example: String,
    val answers: List<Answer>,
)

/** Compliance levels from Regulation 756/2026. */
enum class ComplianceLevel {
    @JsonProperty("basic") BASIC,
    @JsonProperty("substantial") SUBSTANTIAL,
    @JsonProperty("high") HIGH,
}

enum class Answer {
    @JsonProperty("yes") YES,
    @JsonProperty("partial") PARTIAL,
    @JsonProperty("no") NO,
    @JsonProperty("unknown") UNKNOWN,
}
