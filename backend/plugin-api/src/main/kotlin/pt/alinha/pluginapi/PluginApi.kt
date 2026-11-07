package pt.alinha.pluginapi

import java.time.Instant
import java.util.UUID

/** Tipo de plugin, como no diagrama de arquitetura. */
enum class PluginKind { FEATURE, FRAMEWORK, PROVIDER }

/**
 * Manifest de um plugin. Espelha o `plugin.yaml` do repo do plugin e é
 * registado no Module Registry do Core no arranque.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val kind: PluginKind,
    val version: String,
    /** Nomes dos eventos que o plugin publica (ex.: "AssessmentSubmitted"). */
    val publishes: Set<String> = emptySet(),
    /** Nomes dos eventos que o plugin consome. */
    val consumes: Set<String> = emptySet(),
    /** Recursos RBAC que o plugin declara (ex.: "assessment:read"). */
    val permissions: Set<String> = emptySet(),
)

/** Cada plugin expõe exatamente um bean que implementa esta interface. */
interface AlinhaPlugin {
    val manifest: PluginManifest
}

@JvmInline
value class TenantId(val value: UUID)

/**
 * Evento de domínio. Publicado com o ApplicationEventPublisher do Spring e
 * persistido pelo Spring Modulith (event publication registry).
 * Os plugins só comunicam entre si por eventos e por contratos «api».
 */
interface DomainEvent {
    val tenantId: TenantId
    val occurredAt: Instant
}
