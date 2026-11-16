# Access control (NFR07, NFR08)

Every API request needs a valid Keycloak access token and an explicit permission. Only `/actuator/health` is public. Other actuator endpoints are denied. Spring's internal error dispatches (rendering an error response) also skip the token; a direct request to `/error` still needs one.

## Roles

| Realm role | Person |
|---|---|
| `consultant` | Consultant |
| `responsible` | Designated Officer |
| `analyst` | Analyst |

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

The application refuses to start if a controller endpoint has no `@RequiresPermission`, or if an annotation names a permission that no plugin declares, so a forgotten annotation or a typo such as `"assesment"` fails at startup instead of answering 403 to everyone. Spring's `/error` controller is the only exemption. At request time the interceptor still denies any endpoint without a permission, as a backstop. The startup check only sees annotated controllers (`@RequestMapping` and friends); functional routes are not checked at startup and are always denied at request time, so plugins must use annotated controllers.

An endpoint open to every signed-in role (such as a plugin's `ping`) uses `@RequiresPermission("plugins")`, the core's `plugins:read`.

## Tenant scope

Realm roles are realm-wide: a `responsible` or `analyst` token has the same permissions in every organisation. `@RequiresPermission` decides *what* a user may do, not *for which organisation*, so on its own it does not stop cross-tenant access.

Tenant isolation comes from `TenantContext` (ADR 0002, #8), which must check the token's `tenants` claim, and from filtering every query by the current tenant. This RBAC layer does not provide either.

## Declaring permissions in a plugin

Declare permissions and their roles in the plugin's `PluginManifest` (Kotlin). That is the only place they are read: `plugin.yaml` is descriptive and is not checked, so do not list permissions there. The application refuses to start if:

- a role is unknown;
- a mapped permission is not in the plugin's own `permissions`;
- two plugins declare the same permission (each permission has exactly one owner);
- a plugin declares a core permission (`plugins:read`).

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

| Endpoint | Permission | Consultant | Designated Officer | Analyst |
|---|---|---|---|---|
| `GET /actuator/health` | public | yes | yes | yes |
| `GET /api/plugins` | `plugins:read` | yes | yes | yes |
| `GET /api/<plugin-id>/ping` | `plugins:read` | yes | yes | yes |
| `GET /api/audit/events` | `audit:read` | yes | yes | no |
