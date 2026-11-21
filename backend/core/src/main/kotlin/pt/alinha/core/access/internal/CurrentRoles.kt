package pt.alinha.core.access.internal

import org.springframework.security.core.context.SecurityContextHolder

/** Realm roles of the caller of the current request, without the `ROLE_` prefix. */
internal fun currentRoles(): Set<String> =
    SecurityContextHolder.getContext().authentication?.authorities.orEmpty()
        .mapNotNull { it.authority }
        .filter { it.startsWith(ROLE_PREFIX) }
        .map { it.removePrefix(ROLE_PREFIX) }
        .toSet()
