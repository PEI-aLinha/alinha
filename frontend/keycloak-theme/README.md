# @alinha/keycloak-theme

The Keycloak login theme (`alinha`): login, registration, email verification, TOTP setup and code, password reset,
info and error pages. Built with [Keycloakify](https://docs.keycloakify.dev) from the MS3 prototype's onboarding
screens (`frontend-prototype`, `src/features/organization/onboarding/`), with the `@alinha/ui` components and tokens.
Issue #128.

```bash
pnpm --filter @alinha/keycloak-theme dev                   # mocked pages, no Keycloak: http://localhost:5174/?page=register.ftl
pnpm --filter @alinha/keycloak-theme build-keycloak-theme  # dist_keycloak/alinha-keycloak-theme.jar (needs Maven)
pnpm --filter @alinha/keycloak-theme test
```

`deploy/docker-compose.dev.yml` mounts `dist_keycloak/` into Keycloak's providers folder and the realm sets
`loginTheme: alinha`. `start-dev` does not cache themes, but a new jar needs a restart:
`docker compose -f deploy/docker-compose.dev.yml restart keycloak`.

## How it is organised

- `src/login/Template.tsx`: the layout of every page (brand panel "Tudo alinhado." with the registration steps,
  language links, Keycloak's message). Below `lg` the panel goes away.
- `src/login/pages/`: the pages we designed. Every other page (update profile, delete credential, ...) is
  Keycloakify's default markup inside our Template, styled through `classes` in `KcPage.tsx`.
- `src/login/i18n.ts`: our texts (`alinha*` keys) in `pt` and `en`, and overrides of Keycloak's own messages in
  plainer European Portuguese. Use `msg()`/`msgStr()`; never hardcode text in a page.
- The forms post straight to Keycloak (`url.loginAction`, `url.registrationAction`) with Keycloak's field names.
  Keep them when changing a page, or Keycloak ignores the field.

## Rules

- Keycloak messages can hold HTML: render them with `kcSanitize` (`Alert`) or as text with `plainText`. Never
  `dangerouslySetInnerHTML` without `kcSanitize`.
- Registration writes out first name, last name and email by hand. A new user-editable attribute in the realm's user
  profile needs its field in `pages/Register.tsx`.
- The registration steps show only on pages that only self-registered users reach (`FLOW_STEP` in `Template.tsx`).
