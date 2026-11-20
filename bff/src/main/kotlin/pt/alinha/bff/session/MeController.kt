package pt.alinha.bff.session

import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
class MeController(private val service: MeService) {

    /** Contract in bff/README.md: 401 (not a redirect) when there is no session. */
    @GetMapping("/api/me")
    fun me(
        authentication: Authentication,
        @AuthenticationPrincipal user: OidcUser,
        exchange: ServerWebExchange,
    ): Mono<MeResponse> = service.me(exchange, authentication, user)
}
