package pt.alinha.bff.auth

import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/auth")
class AuthController(private val refreshService: TokenRefreshService) {

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun refresh(authentication: Authentication, exchange: ServerWebExchange): Mono<Void> =
        refreshService.refresh(exchange, authentication)
}
