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
 * transaction and acknowledges it only after that commits. Each round first claims entries left unacknowledged
 * (XPENDING + XCLAIM; Spring Data Redis has no XAUTOCLAIM), then reads new ones. A result applied twice is ignored
 * the second time, so redelivery is safe. While the database is down nothing is read or claimed, so a database
 * outage never uses up an entry's deliveries. See docs/jobs.md.
 */
@Component
class JobResultConsumer(
    private val streams: RedisJobStreams,
    private val handler: JobResultHandler,
    private val outbox: JobOutboxRepository,
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
    fun consumeOnce(): Int {
        // Every read or claim counts as a delivery. With the database down none could be applied, and enough rounds
        // would dead-letter valid results: wait for it instead, as the relay waits for Redis (RNF02).
        if (!outbox.isReachable()) {
            log.warn("Database unreachable, job results wait in Redis")
            return 0
        }
        return try {
            if (!groupReady) {
                streams.ensureResultsGroup()
                groupReady = true
            }
            recover() + streams.readResults(consumerName, properties.results.batchSize).count(::process)
        } catch (e: DataAccessException) {
            // Redis down: like the relay, wait for it. Anything read but not acknowledged is delivered again.
            if (e.isNoGroup()) groupReady = false
            log.warn("Reading job results failed: {}", e.message)
            0
        } catch (e: DatabaseUnavailable) {
            log.warn("Database went down while applying job results, the rest wait in Redis")
            0
        }
    }

    /**
     * Claims entries nobody acknowledged within claim-min-idle: their consumer died, or applying them failed (the
     * database was down). One delivered max-deliveries times already is dead-lettered instead of tried again.
     */
    private fun recover(): Int {
        val settings = properties.results
        val stale = streams.staleResults(settings.claimMinIdle, settings.batchSize)
        val exhausted = stale.filter { it.totalDeliveryCount >= settings.maxDeliveries }.map { it.id }.toSet()
        return streams.claimResults(consumerName, settings.claimMinIdle, stale.map { it.id }).count { record ->
            if (record.id in exhausted) {
                log.error("Job result {} not applied after {} deliveries, dead-lettered", record.id, settings.maxDeliveries)
                streams.deadLetter(record, "not applied after ${settings.maxDeliveries} deliveries")
                streams.ack(record.id)
                true
            } else {
                process(record)
            }
        }
    }

    /** @return true when the entry was acknowledged. */
    private fun process(record: ResultRecord): Boolean {
        if (isTooLarge(record)) {
            val limit = properties.results.maxResultBytes
            log.warn("Job result {} larger than {} bytes, dead-lettered without it", record.id, limit)
            streams.deadLetter(record, "result larger than $limit bytes", dropResult = true)
            streams.ack(record.id)
            return true
        }
        val handling = try {
            handler.handle(record.value[RESULT])
        } catch (e: RuntimeException) {
            // Leave the entry pending, it is delivered again later. A bug counts towards max-deliveries; the database
            // going down does not: stop the round before reading or claiming anything else.
            log.error("Applying job result {} failed, it stays pending", record.id, e)
            if (!outbox.isReachable()) throw DatabaseUnavailable()
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

    private fun isTooLarge(record: ResultRecord) =
        record.value[RESULT]?.let { it.toByteArray(Charsets.UTF_8).size > properties.results.maxResultBytes } == true

    /** Ends the round; the entries already read stay pending and are delivered again. */
    private class DatabaseUnavailable : RuntimeException()
}
