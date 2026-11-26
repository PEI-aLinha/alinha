package pt.alinha.core.compliance

/** Small in-test catalogues: a control "<DOMAIN>.<n>-<L>" with one question per entry in [questions]. */
object TestCatalogues {
    const val BASIC = "basic"
    const val SUBSTANTIAL = "substantial"
    const val HIGH = "high"

    /** basic, substantial, high without evidence bars, so the answer rules can be tested on their own. */
    val PLAIN_LEVELS = QNRCS_LEVELS.map { Level(it.id, it.label) }

    fun control(id: String, level: String, questions: Int = 1, technical: Boolean = false) = Control(
        id = id,
        title = id,
        level = level,
        technical = technical,
        evidence = emptyList(),
        questions = (1..questions).map {
            Question("$id-Q$it", "q", "e", "x", listOf(Answer.YES, Answer.PARTIAL, Answer.NO, Answer.UNKNOWN))
        },
    )

    fun catalogue(
        vararg controls: Control,
        requirements: List<Requirement> = emptyList(),
        levels: List<Level> = PLAIN_LEVELS,
    ) = Catalogue(
        framework = "test-fw",
        version = 3,
        title = "Test",
        sources = emptyList(),
        requirements = requirements,
        controls = controls.toList(),
        levels = levels,
    )

    /** Every question of [controls] answered with [answer]. */
    fun answerAll(catalogue: Catalogue, answer: Answer = Answer.YES): Map<String, Answer> =
        catalogue.controls.flatMap { it.questions }.associate { it.id to answer }
}
