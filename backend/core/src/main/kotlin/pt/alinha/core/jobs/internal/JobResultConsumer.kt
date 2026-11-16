package pt.alinha.core.jobs.internal

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.RESULT
import pt.alinha.core.jobs.internal.RedisJobStreams.Companion.isNoGroup
import java.util.UUID

/**
 * Reads worker results from `alinha:jobs:results` with the consumer group `alinha-core`, applies each one in its own
 * transaction and acknowledges it only after that commits. A result applied twice is ignored the second time, so
 * redelivery is safe. See docs/jobs.md.
 */
@Component
class JobResultConsumer(
    private val streams: RedisJobStreams,
    private val handler: JobResultHandler,
    private val properties: JobsProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val consumerName: String = properties.results.consumerName.ifBlank { "core-${UUID.randomUUID()}" }

    @Volatile private var groupReady = false

    @Scheduled(fixedDelayString = "\${alinha.jobs.results.poll-interval-ms:1000}")
    fun poll() {
        if (properties.results.enabled) consumeOnce()
    }

    /** @return entries acknowledged in this round (applied, ignored or dead-lettered). */
    fun consumeOnce(): Int = try {
        if (!groupReady) {
            streams.ensureResultsGroup()
            groupReady = true
        }
        streams.readResults(consumerName, properties.results.batchSize).count(::process)
    } catch (e: DataAccessException) {
        // Redis down: like the relay, wait for it. Anything read but not acknowledged is delivered again.
        if (e.isNoGroup()) groupReady = false
        log.warn("Reading job results failed: {}", e.message)
        0
    }

    /** @return true when the entry was acknowledged. */
    private fun process(record: ResultRecord): Boolean {
        val handling = try {
            handler.handle(record.value[RESULT])
        } catch (e: RuntimeException) {
            // Database down or a bug: leave the entry pending, it is delivered again later.
            log.error("Applying job result {} failed, it stays pending", record.id, e)
            return false
        }
        when (handling) {
            is ResultHandling.Rejected -> {
                log.warn("Job result {} rejected and dead-lettered: {}", record.id, handling.reason)
                streams.deadLetter(record, handling.reason)
            }
            is ResultHandling.Ignored -> log.info("Job result {} ignored: {}", record.id, handling.reason)
            ResultHandling.Applied -> Unit
        }
        streams.ack(record.id)
        return true
    }
}
