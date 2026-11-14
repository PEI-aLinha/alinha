package pt.alinha

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
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

    private val pluginCode = arrayOf("pt.alinha.core..", "pt.alinha.pluginapi..")

    @Test
    fun `plugins do not use JdbcTemplate`() {
        val rule: ArchRule = noClasses()
            .that().resideInAPackage("pt.alinha..")
            .and().resideOutsideOfPackages(*pluginCode)
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
            .orShould().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
            .because("JdbcTemplate is not filtered by tenant (docs/tenancy.md)")
            .allowEmptyShould(true)

        rule.check(classes)
    }

    @Test
    fun `plugins do not declare native queries`() {
        val nativeQuery = object : DescribedPredicate<JavaMethod>("annotated as a native query") {
            override fun test(method: JavaMethod): Boolean = method.annotations.any {
                it.rawType.name == "org.springframework.data.jpa.repository.NativeQuery" ||
                    (it.rawType.name == "org.springframework.data.jpa.repository.Query" && it.get("nativeQuery").orElse(false) == true)
            }
        }
        val rule: ArchRule = methods()
            .that(nativeQuery)
            .should().beDeclaredInClassesThat().resideInAnyPackage(*pluginCode)
            .because("native queries are not filtered by tenant (docs/tenancy.md)")
            .allowEmptyShould(true)

        rule.check(classes)
    }
}
