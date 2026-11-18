# Access control (NFR07, NFR08)

Every API request needs a valid Keycloak access token and an explicit permission. Only `/actuator/health` is public. Other actuator endpoints are denied.

## Roles

| Realm role | Person |
|---|---|
| `consultant` | Consultant |
| `responsible` | Designated Officer |
| `analyst` | Analyst |
| `registrant` | Account with no organization yet (self-registration, ADR 0004) |

## Token contract

The backend expects these from the Keycloak realm (issue #5):

- Issuer: `KEYCLOAK_ISSUER_URI`, by default `http://localhost:8180/realms/alinha`.
- Audience (`aud`) contains `alinha-api`.
- Roles in `realm_access.roles`.

## Protecting an endpoint

Annotate the controller method, or the class for a default. A method annotation replaces the class annotation.

```kotlin
@RequiresPermission("assessment", "edit")
@PostMapping("/api/assessments")
fun create(...) = ...
```

An endpoint without `@RequiresPermission` always answers 403. Functional routes are denied too, so plugins must use annotated controllers.

The core grants every known role `plugins:read` (plugin discovery) and `session:read` (the BFF's `/internal/me`).

## Declaring permissions in a plugin

Declare permissions and their roles in the plugin's `PluginManifest`, and keep `plugin.yaml` in sync. The application refuses to start if a role is unknown or a mapped permission is not in `permissions`.

```kotlin
PluginManifest(
    id = "assessment",
    name = "Assessment",
    kind = PluginKind.FEATURE,
    version = "0.1.0",
    permissions = setOf("assessment:read", "assessment:edit"),
    rolePermissions = mapOf(
        "consultant" to setOf("assessment:read"),
        "responsible" to setOf("assessment:read", "assessment:edit"),
        "analyst" to setOf("assessment:read", "assessment:edit"),
    ),
)
```

Analysts follow least privilege: never grant them global results or roadmap permissions.

## Endpoint x role matrix

Add a row with each new endpoint.

| Endpoint | Permission | Consultant | Designated Officer | Analyst | Registrant |
|---|---|---|---|---|---|
| `GET /actuator/health` | public | yes | yes | yes | yes |
| `GET /api/plugins` | `plugins:read` | yes | yes | yes | yes |
| `GET /api/me` | authenticated (BFF) | yes | yes | yes | yes |
| `GET /internal/me` | `session:read` (BFF only) | yes | yes | yes | yes |
| `POST /auth/refresh` | authenticated (BFF) | yes | yes | yes | yes |
| `POST /auth/logout` | authenticated (BFF) | yes | yes | yes | yes |
