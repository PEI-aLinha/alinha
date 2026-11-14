package pt.alinha.core.compliance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener

/** Starts a real Spring application, so the @PostConstruct and ApplicationReadyEvent wiring is exercised. */
class CatalogueRegistryWiringTest {

    @Test
    fun `a listener in another bean receives the versions once the application is ready`() {
        // Tests run from backend/core, so the repo's frameworks folder is two levels up.
        SpringApplicationBuilder(TestApplication::class.java)
            .web(WebApplicationType.NONE)
            .properties("alinha.frameworks.dir=../../frameworks")
            .run()
            .use { context ->
                val received = context.getBean(Recorder::class.java).received

                assertThat(received).anyMatch { it.framework == "nis2-qnrcs" && it.version == 1 }
                assertThat(received).hasSameSizeAs(context.getBean(CatalogueRegistry::class.java).all())
            }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CatalogueRegistry::class, Recorder::class)
    class TestApplication

    class Recorder {
        val received = mutableListOf<CatalogueVersionPublished>()

        @EventListener
        fun on(event: CatalogueVersionPublished) {
            received += event
        }
    }
}
