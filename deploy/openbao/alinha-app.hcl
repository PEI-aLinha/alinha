# Least-privilege policy for the app: read platform secrets, manage per-tenant provider credentials.
# Also loaded by OpenBaoTenantSecretStoreTest, so the tests run with exactly this policy.
path "secret/data/alinha/app" { capabilities = ["read"] }
# Spring Cloud Vault also reads alinha/app/<profile> for each active profile; fail-fast turns a 403 there into a startup failure.
path "secret/data/alinha/app/*" { capabilities = ["read"] }
path "secret/data/tenants/*" { capabilities = ["create", "read", "update", "delete"] }
# Only `delete` (purges every version). No `list`/`read`: the app must not enumerate tenants or read version metadata.
path "secret/metadata/tenants/*" { capabilities = ["delete"] }
