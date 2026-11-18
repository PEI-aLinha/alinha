# Access control (NFR07, NFR08)

Every API request needs a valid Keycloak access token and an explicit permission. Only `/actuator/health` is public. Other actuator endpoints are denied.

## Roles

| Realm role | Person |
|---|---|
| `consultant` | Consultant |
| `responsible` | Designated Officer |
| `analyst` | Analyst |
| `registrant` | New account that has not registered its organization yet (ADR 0004). Gets `plugins:read` and only what a plugin maps to it, e.g. `organization:register`. Replaced by `responsible` when the organization is registered |

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

## Declaring permissions in a plugin

Declare permissions, their roles and the plugin's menu entries in its `PluginManifest`, and keep `plugin.yaml` in sync (`PluginYamlConsistencyTest` fails the build otherwise). The application refuses to start when:

- two plugins have the same id, or an id is not kebab-case;
- a permission is not in `resource:action` form;
- a role is unknown, or a mapped permission is not in `permissions`;
- a menu entry (`nav`) needs a permission no plugin declares and the core does not own;
- two menu entries share the same area and path;
- an endpoint's `@RequiresPermission` names a permission no plugin declares and the core does not own.

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
    nav = listOf(NavEntry("Avaliação", "assessment", NavArea.CLIENT, resource = "assessment")),
)
```

`GET /api/plugins` returns every manifest with `nav` limited to the entries the caller may see: in an area its roles open (`consultant` → consultant, `responsible`/`analyst` → client, `registrant` → onboarding, as the shell routes them) and behind a permission it holds. Role mappings stay on the server.

Analysts follow least privilege: never grant them global results or roadmap permissions.

## Endpoint x role matrix

Add a row with each new endpoint.

| Endpoint | Permission | Consultant | Designated Officer | Analyst |
|---|---|---|---|---|
| `GET /actuator/health` | public | yes | yes | yes |
| `GET /api/plugins` | `plugins:read` | yes | yes | yes |
| `GET /api/audit/events` | `audit:read` | yes | yes | no |
| `POST /api/organization/registration` | `organization:register` (only `registrant`) | no | no | no |
| `GET /api/organization/profile` | `organization:read` | yes | yes | yes |
| `PUT /api/organization/profile` | `organization:edit` | yes | yes | no |
