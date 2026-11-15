package pt.alinha.core.jobs.internal

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/** Publishes committed outbox rows to Redis Streams. At-least-once: workers dedupe by (jobId, attempt). */
@Component
class OutboxRelay(
    private val outbox: JobOutboxRepository,
    private val streams: RedisJobStreams,
    private val tx: TransactionTemplate,
    private val properties: JobsProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${alinha.jobs.relay.poll-interval-ms:1000}")
    fun poll() {
        if (properties.relay.enabled) relayOnce()
    }

    /** One batch in one transaction (row locks held until commit). @return jobs published. */
    fun relayOnce(): Int = tx.execute { publishDue() } ?: 0

    private fun publishDue(): Int {
        var published = 0
        for (job in outbox.lockDuePending(properties.relay.batchSize)) {
            try {
                streams.publish(job)
            } catch (e: DataAccessException) {
                recordFailure(job, e)
                break // Redis is most likely down: the rest of the batch waits for the next round
            }
            outbox.markPublished(job.id)
            published++
        }
        return published
    }

    private fun recordFailure(job: OutboxJob, e: Exception) {
        val failures = job.publishFailures + 1
        val dead = failures >= properties.relay.maxPublishFailures
        val delay = publishBackoff(failures, properties.relay.backoffBase, properties.relay.backoffMax)
        outbox.markPublishFailed(job.id, describe(e), delay.toMillis(), dead)
        log.warn("Publishing job {} ({}) failed {} time(s){}", job.id, job.type, failures, if (dead) ", now dead" else "", e)
    }

    private fun describe(e: Exception) = "${e.javaClass.simpleName}: ${e.message}".take(MAX_ERROR_LENGTH)

    private companion object {
        const val MAX_ERROR_LENGTH = 500
    }
}

/** base × 2^(failures-1), capped at [max]. */
fun publishBackoff(failures: Int, base: Duration, max: Duration): Duration {
    val factor = 1L shl (failures - 1).coerceIn(0, MAX_SHIFT)
    return base.multipliedBy(factor).coerceAtMost(max)
}

private const val MAX_SHIFT = 30
