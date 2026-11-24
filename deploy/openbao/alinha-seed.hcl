# Production's openbao-seed job (deploy/openbao/secrets.sh): writes the platform secrets and nothing else.
# It cannot read them back, touch tenants' credentials (secret/tenants/*), write policies or create tokens.
path "secret/data/alinha/app" { capabilities = ["create", "update"] }
path "secret/data/alinha/ai-service" { capabilities = ["create", "update"] }
# A provider's platform credential (e.g. the GitHub App key of code-repository), read by the job consumer.
path "secret/data/providers/+" { capabilities = ["create", "update"] }
