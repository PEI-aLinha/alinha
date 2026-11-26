package pt.alinha.core.compliance

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A framework catalogue, read from frameworks/<framework>/v<n>/catalogue.yaml.
 * Format: contracts/frameworks/catalogue.v2.schema.json (v1, without `levels`, still read: see [levels]).
 */
data class Catalogue(
    val framework: String,
    val version: Int,
    val title: String,
    val sources: List<String>,
    val requirements: List<Requirement>,
    val controls: List<Control>,
    /**
     * The framework's own level scale, lowest first (ADR 0007). Controls point at a level id of this scale and levels
     * are compared by position, never across catalogues. A v1 catalogue has none and gets the QNRCS compliance levels.
     */
    val levels: List<Level> = QNRCS_LEVELS,
) {
    /** Position of [levelId] in [levels] (0 = lowest), or -1 when the scale has no such level. */
    fun levelIndex(levelId: String): Int = levels.indexOfFirst { it.id == levelId }
}

/** One level of a catalogue's scale: e.g. `substantial` "Substancial" (QNRCS) or `3` "Definido" (SOC-CMM). */
data class Level(val id: String, val label: String)

/** Compliance levels of Regulation 756/2026: the scale of every v1 catalogue. */
val QNRCS_LEVELS = listOf(Level("basic", "Básico"), Level("substantial", "Substancial"), Level("high", "Elevado"))

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
    /** Id of a level of the catalogue's [Catalogue.levels]: the lowest level that requires this control. */
    val level: String,
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

enum class Answer {
    @JsonProperty("yes") YES,
    @JsonProperty("partial") PARTIAL,
    @JsonProperty("no") NO,
    @JsonProperty("unknown") UNKNOWN,
}
