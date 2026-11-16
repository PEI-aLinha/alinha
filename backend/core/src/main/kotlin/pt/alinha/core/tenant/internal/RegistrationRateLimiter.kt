package pt.alinha.core.tenant.internal

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val WINDOW: Duration = Duration.ofHours(1)
/** Sweep idle clients after this many calls, so the map cannot grow without bound. */
private const val SWEEP_EVERY = 1_000

internal const val TOO_MANY_REGISTRATIONS = "TOO_MANY_REGISTRATIONS"

/** Limits per client (IP) of self-registration (ADR 0004). */
@ConfigurationProperties("alinha.provisioning")
data class ProvisioningProperties(
    /** Registration attempts one client may start per hour. */
    val maxPerClientPerHour: Int = 5,
)

/**
 * One registration in flight and [ProvisioningProperties.maxPerClientPerHour] attempts per hour for each client IP,
 * so many accounts from one place cannot take every provisioning slot. The IP is the request's remote address; with
 * `server.forward-headers-strategy: native` Tomcat replaces it with `X-Forwarded-For` only when the request comes from
 * an internal proxy (the BFF), so a client cannot choose its own.
 *
 * In memory: enough for a single instance. Several instances need a shared limit (Redis, in the BFF: #14).
 * Calls outside an HTTP request (tests, jobs) are not limited.
 */
@Component
@EnableConfigurationProperties(ProvisioningProperties::class)
class RegistrationRateLimiter internal constructor(
    private val properties: ProvisioningProperties,
    private val clock: Clock,
) {
    @Autowired constructor(properties: ProvisioningProperties) : this(properties, Clock.systemUTC())

    private val attempts = ConcurrentHashMap<String, List<Instant>>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val calls = AtomicInteger()

    /** Runs [block] if this client may start a registration now; otherwise throws 429 `TOO_MANY_REGISTRATIONS`. */
    fun <T> limit(block: () -> T): T {
        val client = currentClient() ?: return block()
        if (!inFlight.add(client)) throw tooMany()
        try {
            if (!record(client)) throw tooMany()
            return block()
        } finally {
            inFlight.remove(client)
        }
    }

    private fun tooMany() =
        ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many registrations from this client").apply {
            body.setProperty("code", TOO_MANY_REGISTRATIONS)
        }

    private fun record(client: String): Boolean {
        val now = clock.instant()
        val since = now.minus(WINDOW)
        var allowed = false
        attempts.compute(client) { _, previous ->
            val recent = previous.orEmpty().filter { it.isAfter(since) }
            allowed = recent.size < properties.maxPerClientPerHour
            if (allowed) recent + now else recent
        }
        if (calls.incrementAndGet() % SWEEP_EVERY == 0) sweep(since)
        return allowed
    }

    private fun sweep(since: Instant) {
        attempts.entries.removeIf { (_, times) -> times.none { it.isAfter(since) } }
    }

    private fun currentClient(): String? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.remoteAddr?.let(::clientKey)
}

private val IP_LITERAL = Regex("^[0-9a-fA-F:.]+$")
private const val IPV6_PREFIX_BYTES = 8

/**
 * The key a client is limited by: an IPv4 address as is (IPv4-mapped IPv6 included), an IPv6 address by its /64,
 * since one subscriber usually gets a whole /64 and could otherwise rotate addresses freely.
 */
internal fun clientKey(address: String): String {
    if (!IP_LITERAL.matches(address)) return address // never resolve a name
    val ip = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return address
    if (ip !is Inet6Address) return ip.hostAddress
    return "v6:" + ip.address.take(IPV6_PREFIX_BYTES).joinToString("") { "%02x".format(it) } + "::/64"
}
