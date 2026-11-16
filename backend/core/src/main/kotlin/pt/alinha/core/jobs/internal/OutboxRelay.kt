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

    /**
     * Up to batch-size jobs, each in its own transaction: a row lock is held for one XADD at most (the Redis timeout),
     * so JobResultConsumer, which waits on that lock, never waits for a whole batch. @return jobs published.
     */
    fun relayOnce(): Int {
        var published = 0
        repeat(properties.relay.batchSize) {
            when (tx.execute { publishNext() }) {
                Outcome.PUBLISHED -> published++
                Outcome.FAILED -> Unit
                Outcome.REDIS_DOWN, Outcome.NONE_DUE, null -> return published
            }
        }
        return published
    }

    private fun publishNext(): Outcome {
        val job = outbox.lockDuePending(1).firstOrNull() ?: return Outcome.NONE_DUE
        try {
            streams.publish(job)
        } catch (e: DataAccessException) {
            // Spring Data Redis reports every Redis-side problem (down, timeout, refusing writes) this way. That is
            // the infrastructure, not this job: never count it towards `dead`, or an outage longer than the
            // backoff would kill every pending job (RNF02).
            deferForOutage(job, e)
            return Outcome.REDIS_DOWN // the other due jobs wait for the next round
        } catch (e: RuntimeException) {
            // Anything else is a problem with this job or with our code. Catch it here, otherwise the transaction
            // rolls back, publish_failures never grows, and the same row heads every round. Redis answered, so the
            // other due jobs still go; this one is backing off and is not picked again in this round.
            recordFailure(job, e)
            return Outcome.FAILED
        }
        outbox.markPublished(job.id)
        return Outcome.PUBLISHED
    }

    private enum class Outcome { PUBLISHED, FAILED, REDIS_DOWN, NONE_DUE }

    /** Short fixed delay, no failure counted: the row goes behind the other due rows and never ends `dead`. */
    private fun deferForOutage(job: OutboxJob, e: DataAccessException) {
        outbox.deferPublish(job.id, describe(e), properties.relay.backoffBase.toMillis())
        log.warn("Redis unavailable, job {} ({}) stays pending: {}", job.id, job.type, describe(e))
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
