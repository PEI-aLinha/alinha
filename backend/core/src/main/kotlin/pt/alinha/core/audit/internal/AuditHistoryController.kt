package pt.alinha.core.audit.internal

import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pt.alinha.core.access.RequiresPermission
import pt.alinha.core.audit.AuditEntry
import pt.alinha.core.audit.AuditFilter
import pt.alinha.core.audit.AuditHistory
import java.time.Instant

data class AuditPage(val items: List<AuditEntry>, val page: Int, val size: Int, val total: Long)

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
        @RequestParam(defaultValue = "50") size: Int,
    ): AuditPage {
        val pageable = PageRequest.of(
            page.coerceAtLeast(0),
            size.coerceIn(1, MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "occurredAt"),
        )
        val result = history.find(AuditFilter(entityType, entityId, actor, from, to), pageable)
        return AuditPage(result.content, result.number, result.size, result.totalElements)
    }

    private companion object {
        const val MAX_PAGE_SIZE = 200
    }
}
