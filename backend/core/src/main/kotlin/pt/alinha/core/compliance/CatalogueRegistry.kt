package pt.alinha.core.compliance

import java.nio.file.Path
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Announces that a catalogue version is available. Not tenant-specific: a catalogue applies to every
 * tenant. Consumers must be idempotent, since every version is announced again on each startup.
 */
data class CatalogueVersionPublished(
    val framework: String,
    val version: Int,
    val title: String,
    val occurredAt: Instant = Instant.now(),
)

/**
 * Keeps the loaded catalogues in memory. A published version is never edited (NFR05):
 * assessments store (framework, version) and read their questions with [get].
 */
@Component
class CatalogueRegistry(
    @Value("\${alinha.frameworks.dir:../../frameworks}") frameworksDir: String,
    private val events: ApplicationEventPublisher,
) {
    private val loader = CatalogueLoader(Path.of(frameworksDir))

    @Volatile
    private var catalogues: Map<CatalogueKey, Catalogue> = emptyMap()

    companion object {
        private val log = LoggerFactory.getLogger(CatalogueRegistry::class.java)
    }

    /** Loads the catalogues when the application starts. An invalid catalogue stops the start-up. */
    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() = reload()

    /**
     * Reads the frameworks folder again and announces every version not loaded before.
     * Versions already in memory are never removed or replaced: a loaded version stays
     * unchanged for the lifetime of the application (NFR05). If a published version on disk
     * differs from the in-memory copy, a warning is logged and the in-memory copy is kept.
     */
    @Synchronized
    fun reload() {
        val loaded = loader.loadAll().associateBy { CatalogueKey(it.framework, it.version) }
        loaded.forEach { (key, fromDisk) ->
            val inMemory = catalogues[key]
            if (inMemory != null && inMemory != fromDisk) {
                log.warn(
                    "Published catalogue {}/{} changed on disk; keeping the in-memory copy (NFR05)",
                    key.framework, key.version,
                )
            }
        }
        val newVersions = loaded.keys - catalogues.keys
        catalogues = loaded + catalogues
        newVersions.sortedWith(compareBy({ it.framework }, { it.version })).forEach { key ->
            val catalogue = loaded.getValue(key)
            events.publishEvent(CatalogueVersionPublished(key.framework, key.version, catalogue.title))
        }
    }

    /** A specific version — what an existing assessment uses. */
    fun get(framework: String, version: Int): Catalogue? = catalogues[CatalogueKey(framework, version)]

    /** The newest version of a framework — what a new assessment starts with. */
    fun latest(framework: String): Catalogue? =
        catalogues.values.filter { it.framework == framework }.maxByOrNull { it.version }

    fun all(): List<Catalogue> = catalogues.values.sortedWith(compareBy({ it.framework }, { it.version }))

    /**
     * Canonical controls: the same control id in several frameworks is the same measure, so one answer
     * counts for all of them. Returns the frameworks (latest versions) that use the control.
     */
    fun frameworksWithControl(controlId: String): List<String> =
        catalogues.values
            .groupBy { it.framework }
            .mapValues { (_, versions) -> versions.maxBy { it.version } }
            .filterValues { catalogue -> catalogue.controls.any { it.id == controlId } }
            .keys
            .sorted()

    private data class CatalogueKey(val framework: String, val version: Int)
}
