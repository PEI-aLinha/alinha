package pt.alinha.core.compliance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

/** Starts a real Spring application, so the @PostConstruct and ApplicationReadyEvent wiring is exercised. */
class CatalogueRegistryWiringTest {

    @Test
    fun `listeners in other beans receive the versions once the application is ready`() {
        // Tests run from backend/core, so the repo's frameworks folder is two levels up.
        SpringApplicationBuilder(TestApplication::class.java)
            .web(WebApplicationType.NONE)
            .properties("alinha.frameworks.dir=../../frameworks")
            .run()
            .use { context ->
                val loaded = context.getBean(CatalogueRegistry::class.java).all().map { it.framework to it.version }
                assertThat(loaded).contains("nis2-qnrcs" to 1)

                val plain = context.getBean(PlainRecorder::class.java).received
                assertThat(plain.map { it.framework to it.version }).isEqualTo(loaded)

                // The project's convention for events between modules: it only runs after a commit.
                val module = context.getBean(ModuleRecorder::class.java).received
                assertThat(module.map { it.framework to it.version }).isEqualTo(loaded)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(CatalogueRegistry::class, PlainRecorder::class, ModuleRecorder::class)
    class TestApplication {
        @Bean
        fun transactionManager(): PlatformTransactionManager = NoDatabaseTransactionManager()

        @Bean
        fun transactionTemplate(transactionManager: PlatformTransactionManager) = TransactionTemplate(transactionManager)
    }

    class PlainRecorder {
        val received = mutableListOf<CatalogueVersionPublished>()

        @EventListener
        fun on(event: CatalogueVersionPublished) {
            received += event
        }
    }

    // Open members: the bean is proxied (the listener is @Transactional), and a final getter would read the proxy.
    open class ModuleRecorder {
        open val received = mutableListOf<CatalogueVersionPublished>()

        // No @EnableAsync here, so the listener runs in the publishing thread, right after the commit.
        @ApplicationModuleListener
        open fun on(event: CatalogueVersionPublished) {
            received += event
        }
    }

    /** Real begin/commit life cycle (so after-commit listeners fire), without a database. */
    class NoDatabaseTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) = Unit
        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }
}
