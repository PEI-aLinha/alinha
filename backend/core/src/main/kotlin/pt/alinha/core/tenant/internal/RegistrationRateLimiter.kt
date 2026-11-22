package pt.alinha.core.tenant.internal

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
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
 * [ProvisioningProperties.maxPerClientPerHour] attempts per hour for each client IP, so many accounts from one place
 * cannot keep taking the provisioning slots. One registration at a time per account is [TenantProvisioner]'s job. The IP is the request's remote address; with
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
    private val calls = AtomicInteger()

    /**
     * Runs [block] if this client may start a registration now; otherwise throws 429 `TOO_MANY_REGISTRATIONS` with
     * `Retry-After`: the time until its oldest attempt leaves the hour.
     *
     * An attempt that ends in a 503 (`REGISTRATION_BUSY`, `IDENTITY_UNAVAILABLE`) is given back: the platform could
     * not serve it, so a client following `Retry-After` through an outage keeps its allowance.
     */
    fun <T> limit(block: () -> T): T {
        val client = currentClient() ?: return block()
        val attempt = clock.instant()
        record(client, attempt)?.let { throw tooMany(it) }
        try {
            return block()
        } catch (e: RegistrationProblem) {
            if (e.statusCode == HttpStatus.SERVICE_UNAVAILABLE) giveBack(client, attempt)
            throw e
        }
    }

    private fun giveBack(client: String, attempt: Instant) {
        attempts.computeIfPresent(client) { _, times ->
            val index = times.indexOf(attempt)
            if (index < 0) times else (times.take(index) + times.drop(index + 1)).ifEmpty { null }
        }
    }

    private fun tooMany(retryAfter: Duration) = RegistrationProblem(
        HttpStatus.TOO_MANY_REQUESTS, "Too many registrations from this client", TOO_MANY_REGISTRATIONS, retryAfter,
    )

    /** Records an attempt and returns null, or, over the limit, how long until the client may try again. */
    private fun record(client: String, now: Instant): Duration? {
        val since = now.minus(WINDOW)
        var wait: Duration? = null
        attempts.compute(client) { _, previous ->
            val recent = previous.orEmpty().filter { it.isAfter(since) }
            if (recent.size < properties.maxPerClientPerHour) {
                recent + now
            } else {
                wait = recent.minOrNull()?.let { Duration.between(now, it.plus(WINDOW)) } ?: WINDOW
                recent
            }
        }
        if (calls.incrementAndGet() % SWEEP_EVERY == 0) sweep(since)
        return wait
    }

    private fun sweep(since: Instant) {
        attempts.entries.removeIf { (_, times) -> times.none { it.isAfter(since) } }
    }

    private fun currentClient(): String? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.remoteAddr?.let(::clientKey)
}

private const val OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
private val IPV4_LITERAL = Regex("^$OCTET(\\.$OCTET){3}$")
/** Hex groups and colons, optionally ending in a dotted IPv4 address; must contain a colon. */
private val IPV6_LITERAL = Regex("^(?=.*:)[0-9a-fA-F:]+(:$OCTET(\\.$OCTET){3})?$")
private const val IPV6_PREFIX_BYTES = 8

/**
 * The key a client is limited by: an IPv4 address as is (IPv4-mapped IPv6 included), an IPv6 address by its /64,
 * since one subscriber usually gets a whole /64 and could otherwise rotate addresses freely. Anything that is not a
 * strict IP literal is kept as is and never resolved, so a name never triggers a DNS lookup.
 */
internal fun clientKey(address: String): String {
    if (IPV4_LITERAL.matches(address)) return address
    if (!IPV6_LITERAL.matches(address)) return address
    // A string with a colon is only ever parsed as an IPv6 literal, never looked up.
    val ip = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return address
    if (ip !is Inet6Address) return ip.hostAddress
    return "v6:" + ip.address.take(IPV6_PREFIX_BYTES).joinToString("") { "%02x".format(it) } + "/64"
}
