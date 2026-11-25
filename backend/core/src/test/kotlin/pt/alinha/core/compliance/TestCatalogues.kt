package pt.alinha.core.compliance

/** Small in-test catalogues: a control "<DOMAIN>.<n>-<L>" with one question per entry in [questions]. */
object TestCatalogues {

    fun control(id: String, level: ComplianceLevel, questions: Int = 1, technical: Boolean = false) = Control(
        id = id,
        title = id,
        level = level,
        technical = technical,
        evidence = emptyList(),
        questions = (1..questions).map {
            Question("$id-Q$it", "q", "e", "x", listOf(Answer.YES, Answer.PARTIAL, Answer.NO, Answer.UNKNOWN))
        },
    )

    fun catalogue(vararg controls: Control, requirements: List<Requirement> = emptyList()) = Catalogue(
        framework = "test-fw",
        version = 3,
        title = "Test",
        sources = emptyList(),
        requirements = requirements,
        controls = controls.toList(),
    )

    /** Every question of [controls] answered with [answer]. */
    fun answerAll(catalogue: Catalogue, answer: Answer = Answer.YES): Map<String, Answer> =
        catalogue.controls.flatMap { it.questions }.associate { it.id to answer }
}
