# The job consumer (workers/job_consumer) reads one provider credential per job: secret/tenants/<tenant>/providers/<id>,
# written by the app through TenantSecretStore. Read only, one level per `+`: no list, no metadata, nothing else.
# The worker builds the path from the job envelope's tenantId and provider (docs/jobs.md, "Credentials for a collector").
# Trust decision: every collector runs in this one process, so a compromised provider package could read any
# provider's credentials for any tenant. Only install reviewed provider packages in the job consumer image.
path "secret/data/tenants/+/providers/+" { capabilities = ["read"] }

# A provider's platform credential, the same for every tenant (e.g. the GitHub App id and private key of
# code-repository), written by operators: secret/providers/<id>. Read only.
# Wider blast radius than one tenant's token: the GitHub App key mints tokens for every client's installation. Keep
# the App's registered permissions read-only, and rotate the key after any doubt about the job consumer image.
path "secret/data/providers/+" { capabilities = ["read"] }
