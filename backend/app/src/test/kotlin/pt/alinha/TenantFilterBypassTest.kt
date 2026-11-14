package pt.alinha

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tenant isolation (RNF03) is enforced by Hibernate's `@TenantId`, which only covers JPA/HQL.
 * Native SQL and `JdbcTemplate` skip it and there is no database-level backstop, so plugins may not use them.
 * See docs/tenancy.md. The core owns the few places that need raw SQL and filters by tenant by hand.
 */
class TenantFilterBypassTest {

    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("pt.alinha")

    @Test
    fun `plugins do not use JdbcTemplate`() = jdbcTemplates.check(classes)

    @Test
    fun `plugins do not declare native queries`() = nativeQueryAnnotations.check(classes)

    @Test
    fun `plugins do not run native SQL through EntityManager or Session`() = nativeSqlCalls.check(classes)

    /** Positive control: the rules use allowEmptyShould, so prove they still flag a known bypass. */
    @Test
    fun `rules flag every bypass in the fixtures`() {
        val fixtures = ClassFileImporter().importPackages(FIXTURE_PACKAGE)
        val expected = mapOf(
            jdbcTemplates to listOf("JdbcTemplateUser"),
            nativeQueryAnnotations to listOf("NativeQueryRepository"),
            nativeSqlCalls to listOf("EntityManagerNativeQuery", "SessionDoWork", "SessionDoReturningWork"),
        )

        expected.forEach { (rule, offenders) ->
            val report = rule.evaluate(fixtures).failureReport.details.joinToString("\n")
            offenders.forEach { offender ->
                assertTrue(report.contains("$FIXTURE_PACKAGE.$offender")) {
                    "Rule '${rule.description}' did not flag $offender:\n$report"
                }
            }
        }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "pt.alinha.fixture.tenantbypass"
        val coreCode = arrayOf("pt.alinha.core..", "pt.alinha.pluginapi..")

        val jdbcTemplates: ArchRule = noClasses()
            .that().resideInAPackage("pt.alinha..")
            .and().resideOutsideOfPackages(*coreCode)
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
            .orShould().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
            .because("JdbcTemplate is not filtered by tenant (docs/tenancy.md)")
            .allowEmptyShould(true)

        val nativeQueryAnnotations: ArchRule = methods()
            .that(object : DescribedPredicate<JavaMethod>("annotated as a native query") {
                override fun test(method: JavaMethod): Boolean = method.annotations.any {
                    it.rawType.name == "org.springframework.data.jpa.repository.NativeQuery" ||
                        (it.rawType.name == "org.springframework.data.jpa.repository.Query" &&
                            it.get("nativeQuery").orElse(false) == true)
                }
            })
            .should().beDeclaredInClassesThat().resideInAnyPackage(*coreCode)
            .because("native queries are not filtered by tenant (docs/tenancy.md)")
            .allowEmptyShould(true)

        val nativeSqlCalls: ArchRule = noClasses()
            .that().resideInAPackage("pt.alinha..")
            .and().resideOutsideOfPackages(*coreCode)
            .should().callMethodWhere(object : DescribedPredicate<JavaMethodCall>(
                "run native SQL (createNative*, doWork, doReturningWork)",
            ) {
                override fun test(call: JavaMethodCall): Boolean {
                    val target = call.target
                    val createsNativeQuery = target.name.startsWith("createNative") &&
                        (target.owner.isAssignableTo("jakarta.persistence.EntityManager") ||
                            target.owner.isAssignableTo("org.hibernate.query.QueryProducer"))
                    val runsJdbcWork = target.name in setOf("doWork", "doReturningWork") &&
                        target.owner.isAssignableTo("org.hibernate.SharedSessionContract")
                    return createsNativeQuery || runsJdbcWork
                }
            })
            .because("native SQL is not filtered by tenant (docs/tenancy.md)")
            .allowEmptyShould(true)
    }
}
