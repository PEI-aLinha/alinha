package pt.alinha.core.jobs

/**
 * Dispatches jobs to the Python workers (PostgreSQL outbox → Redis Streams, RNF02).
 * Each job's format lives in contracts/jobs/<type>.schema.json.
 */
interface JobDispatcher {
    fun dispatch(type: String, payload: Map<String, Any?>): String
}
