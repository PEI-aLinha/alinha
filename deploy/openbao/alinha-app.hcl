# Least-privilege policy for the app: read platform secrets, manage per-tenant provider credentials.
# Also loaded by OpenBaoTenantSecretStoreTest, so the tests run with exactly this policy.
path "secret/data/alinha/app" { capabilities = ["read"] }
path "secret/data/tenants/*" { capabilities = ["create", "read", "update", "delete"] }
path "secret/metadata/tenants/*" { capabilities = ["read", "delete", "list"] }
