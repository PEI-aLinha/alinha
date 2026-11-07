package pt.alinha.core.jobs

/**
 * Despacha jobs para os workers Python (outbox em PostgreSQL → Redis Streams).
 * O formato de cada job está em contracts/jobs/<tipo>.schema.json.
 */
interface JobDispatcher {
    fun dispatch(type: String, payload: Map<String, Any?>): String
}
