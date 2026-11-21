package pt.alinha.bff.session

import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import pt.alinha.bff.BffProperties
import reactor.core.publisher.Mono

/** Read-only calls to the aLinha application, always carrying the session's access token. */
@Component
class AppClient(builder: WebClient.Builder, props: BffProperties) {

    // Boot's builder (a fresh one per injection point) brings the configured codecs and observation.
    private val client = builder.baseUrl(props.appUrl).build()

    // A hung app (DB lock, GC pause) must end in a 502, not a shell that spins forever.
    private val timeout = props.appTimeout

    fun internalMe(token: String): Mono<InternalMe> =
        client.get()
            .uri("/internal/me")
            .headers { it.setBearerAuth(token) }
            .retrieve()
            .bodyToMono(InternalMe::class.java)
            .timeout(timeout)

    fun organization(token: String, tenantId: String): Mono<AppOrganization> =
        client.get()
            .uri("/api/organization/profile")
            .headers {
                it.setBearerAuth(token)
                it.set("X-Alinha-Tenant", tenantId)
            }
            .retrieve()
            .bodyToMono(AppOrganization::class.java)
            .timeout(timeout)
}
