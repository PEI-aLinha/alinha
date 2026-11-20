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
 * the second time, so redelivery is safe. Only attempts that reached a database taking writes count towards
 * max-deliveries (kept in `alinha:jobs:results:attempts`, not the stream's delivery count), so a database outage
 * never uses up a result; max-total-deliveries caps deliveries whatever happened. See docs/jobs.md.
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
        if (!outbox.acceptsWrites()) {
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
     * database was down). Claiming adds a delivery, so max-deliveries is checked against [RedisJobStreams.attempts]
     * in [process], never against the delivery count.
     */
    private fun recover(): Int {
        val settings = properties.results
        val stale = streams.staleResults(settings.claimMinIdle, settings.batchSize)
        // Attempts given back never dead-letter an entry, so the delivery count is the absolute cap: an entry that
        // only ever meets a failing database still ends up dead-lettered instead of blocking the stream for good.
        val overdue = stale.filter { it.totalDeliveryCount >= settings.maxTotalDeliveries }.map { it.id }.toSet()
        return streams.claimResults(consumerName, settings.claimMinIdle, stale.map { it.id }).count { record ->
            if (record.id in overdue) {
                giveUp(record, "delivered ${settings.maxTotalDeliveries} times without being applied")
            } else {
                process(record)
            }
        }
    }

    /**
     * Dead-letters the entry, fails its job when it can (JobFailed, see JobResultHandler.failUnapplied), acks it.
     * Every dead-letter path goes through [dropOversized] first, so an oversized result is never kept or parsed.
     */
    private fun giveUp(record: ResultRecord, reason: String): Boolean {
        if (dropOversized(record)) return true
        log.error("Job result {} {}, dead-lettered", record.id, reason)
        streams.deadLetter(record, reason)
        val failed = try {
            handler.failUnapplied(record.value[RESULT], reason)
        } catch (e: RuntimeException) {
            log.error("Could not fail the job of result {}, it stays published (#86)", record.id, e)
            false
        }
        if (!failed) log.warn("Job of result {} not failed: no open job matches it", record.id)
        streams.ack(record.id)
        return true
    }

    /** @return true when the entry was acknowledged. */
    private fun process(record: ResultRecord): Boolean {
        if (dropOversized(record)) return true
        val maxAttempts = properties.results.maxDeliveries
        if (streams.attempts(record.id) >= maxAttempts) return giveUp(record, "not applied after $maxAttempts attempts")
        streams.beginAttempt(record.id)
        val handling = try {
            handler.handle(record.value[RESULT])
        } catch (e: RuntimeException) {
            // Leave the entry pending, it is delivered again later. A bug counts towards max-deliveries; the database
            // not taking writes does not: give the attempt back and stop the round before reading or claiming more.
            // Only an independent probe decides that. The entry's own error never does: a poison entry could raise
            // an outage SQLState itself and would then never be dead-lettered.
            if (!outbox.acceptsWrites()) {
                log.warn("Database not taking writes while applying job result {}: {}", record.id, e.message)
                streams.giveBackAttempt(record.id)
                throw DatabaseUnavailable()
            }
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

    /**
     * A `result` over max-result-bytes is dead-lettered without it, before anything parses it: it would be stored as
     * is in core.job_outbox. @return true when the entry was dropped (and acknowledged).
     */
    private fun dropOversized(record: ResultRecord): Boolean {
        if (!isTooLarge(record)) return false
        val limit = properties.results.maxResultBytes
        log.warn("Job result {} larger than {} bytes, dead-lettered without it", record.id, limit)
        streams.deadLetter(record, "result larger than $limit bytes", dropResult = true)
        streams.ack(record.id)
        return true
    }

    private fun isTooLarge(record: ResultRecord) =
        record.value[RESULT]?.let { it.toByteArray(Charsets.UTF_8).size > properties.results.maxResultBytes } == true

    /** Ends the round; the entries already read stay pending and are delivered again. */
    private class DatabaseUnavailable : RuntimeException()

}
