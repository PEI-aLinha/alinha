package pt.alinha.core.jobs.internal

import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

typealias ResultRecord = MapRecord<String, String, String>

@Component
class RedisJobStreams(private val redis: StringRedisTemplate) {
    private val streams get() = redis.opsForStream<String, String>()

    /**
     * XADD without MAXLEN: trimming by length would also drop entries no worker has read yet, and the outbox
     * already says `published`, so the job would be lost (RNF02). Only acknowledged entries may be trimmed (#87).
     * `attempt` lives in the entry, not the envelope, so the contract is the same on every retry.
     */
    fun publish(job: OutboxJob): RecordId = checkNotNull(
        streams.add(
            streamKey(job.type, job.version),
            mapOf(ENVELOPE to job.envelope, ATTEMPT to job.attempt.toString()),
        ),
    ) { "Redis returned no record id for job ${job.id}" }

    /** XGROUP CREATE … 0 MKSTREAM: results written before the core first starts are read too. Idempotent. */
    fun ensureResultsGroup() {
        try {
            streams.createGroup(RESULTS_STREAM, ReadOffset.from("0"), RESULTS_GROUP)
        } catch (e: DataAccessException) {
            if (!e.redisError(BUSYGROUP)) throw e
        }
    }

    /** XREADGROUP … > : entries never delivered to any consumer of the group. Does not block. */
    fun readResults(consumer: String, count: Int): List<ResultRecord> =
        streams.read(
            Consumer.from(RESULTS_GROUP, consumer),
            StreamReadOptions.empty().count(count.toLong()),
            StreamOffset.create(RESULTS_STREAM, ReadOffset.lastConsumed()),
        ).orEmpty()

    fun ack(id: RecordId) {
        streams.acknowledge(RESULTS_STREAM, RESULTS_GROUP, id)
    }

    /** Keeps the worker's fields as they came, plus where the entry came from and why it was refused. */
    fun deadLetter(record: ResultRecord, reason: String) {
        streams.add(RESULTS_DLQ, record.value + mapOf(SOURCE_ID to record.id.value, REASON to reason.take(MAX_REASON)))
    }

    companion object {
        const val ENVELOPE = "envelope"
        const val ATTEMPT = "attempt"

        const val RESULTS_STREAM = "alinha:jobs:results"
        const val RESULTS_DLQ = "alinha:jobs:results:dead"
        const val RESULTS_GROUP = "alinha-core"
        const val RESULT = "result"
        const val SOURCE_ID = "sourceId"
        const val REASON = "reason"

        private const val BUSYGROUP = "BUSYGROUP"
        private const val NOGROUP = "NOGROUP"
        private const val MAX_REASON = 500

        fun streamKey(type: String, version: Int) = "alinha:jobs:$type:v$version"

        /** The results group is gone (Redis lost its data): create it again. */
        fun DataAccessException.isNoGroup() = redisError(NOGROUP)

        private fun DataAccessException.redisError(code: String) =
            generateSequence<Throwable>(this) { it.cause }.any { it.message.orEmpty().contains(code) }
    }
}
