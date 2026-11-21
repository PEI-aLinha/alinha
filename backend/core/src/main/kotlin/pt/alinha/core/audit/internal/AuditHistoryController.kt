package pt.alinha.core.audit.internal

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import pt.alinha.core.access.RequiresPermission
import pt.alinha.core.audit.AuditEntry
import pt.alinha.core.audit.AuditFilter
import pt.alinha.core.audit.AuditHistory
import java.time.Instant

/** Same shape as every paginated response in the contract (`PageMeta` + `items`). */
data class AuditPage(val items: List<AuditEntry>, val page: Int, val pageSize: Int, val total: Long)

@RestController
class AuditHistoryController(private val history: AuditHistory) {

    @RequiresPermission("audit", "read")
    @GetMapping("/api/audit/events")
    fun list(
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) entityId: String?,
        @RequestParam(required = false) actor: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") pageSize: Int,
    ): AuditPage {
        if (from != null && to != null && from.isAfter(to)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to")
        }
        val pageable = PageRequest.of(
            page.coerceAtLeast(0),
            pageSize.coerceIn(1, MAX_PAGE_SIZE),
            // id breaks ties (same transaction or millisecond) so offset pages never skip or repeat rows.
            Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id")),
        )
        val result = history.find(AuditFilter(entityType, entityId, actor, from, to), pageable)
        return AuditPage(result.content, result.number, result.size, result.totalElements)
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
