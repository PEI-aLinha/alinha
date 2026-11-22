# Deployment

The production stack runs on a VM behind a reverse proxy that terminates TLS, and answers at
https://pei-alinha.duckdns.org. The VM is **aarch64**, so `publish.yml` builds `linux/arm64` images on GitHub's ARM runners.

```text
gh release create vX.Y.Z  ──►  publish.yml (GitHub runners)  ──►  ghcr.io/pei-alinha/alinha/<image>:X.Y.Z
                                                                          │
Actions → "Deploy to Production VM" → Run  ──►  deploy.yml (self-hosted runner on the VM)  ──►  docker compose up
```

| Piece | Where |
|---|---|
| Images | `deploy/Dockerfile.backend` (app, bff), `Dockerfile.web`, `Dockerfile.keycloak`, `workers/job_consumer/Dockerfile` |
| Stack | `deploy/docker-compose.prod.yml`, compose project `alinha-prod` |
| Secrets | GitHub environment `production` (required reviewers, `main` only). Nothing on the VM's disk. |
| Runner | `~/actions-runner-alinha` on the VM, labels `self-hosted,prod`, systemd service as `ubuntu` |
| Reverse proxy | Nginx on the VM (not in this repo), TLS for `pei-alinha.duckdns.org`; its Docker network is `PROXY_NETWORK` |

## How a request gets in

```text
browser ─► reverse proxy (TLS, PROXY_NETWORK) ─► web (alias alinha-web)
                                                    ├─ /                       shell (static)
                                                    ├─ /api/ /oauth2/ /login/oauth2/ /auth/ ─► bff ─► app
                                                    └─ /kc/  ─► keycloak   (/kc/admin and /kc/realms/master are 404)
```

- The reverse proxy only knows the alias `alinha-web` on the network `PROXY_NETWORK`; the `web` service joins that
  network with that alias, so the proxy needs no change when the stack is redeployed.
- The reverse proxy must send `X-Forwarded-For $remote_addr` (not `$proxy_add_x_forwarded_for`) in the
  `pei-alinha.duckdns.org` block, or a client can choose the address the BFF's rate limiter sees.
- `web` trusts `X-Forwarded-For` only from `TRUSTED_PROXY_CIDR` (the proxy's network) and passes just
  the client address on. The app trusts it only from the BFF's fixed address (`172.31.0.10`).
- Keycloak runs under `/kc` with `KC_HOSTNAME=https://pei-alinha.duckdns.org/kc`, so tokens carry the public issuer.
  The BFF and the app fetch tokens and keys from `http://keycloak:8080/kc/...` inside the network.

## Release and deploy

1. Merge to `main` with CI green.
2. `gh release create vX.Y.Z --generate-notes` (the tag triggers `publish.yml`; wait for the 5 images).
3. Actions → **Deploy to Production VM** → Run workflow on `main`. Empty `image_tag` = latest release. A reviewer approves
   the `production` environment, then the runner pulls the images and runs `up -d --wait`.
4. If it fails, the job prints the last logs. **Get Production Logs** dumps the logs of every container at any time.

Only one of **Deploy**, **Get Production Logs** and **Clean** runs on the VM at a time (`concurrency: prod-vm`);
a second run waits for the first instead of starting halfway through its `up`.

**Rolling back** = running the deploy again with the previous version as `image_tag`. That works only if no Flyway
migration was added since that version: migrations are forward-only, and an older `app` refuses to start against
a schema it does not know (Flyway validation). If one was, roll forward instead: fix it in a new release.

## Secrets and variables (environment `production`)

Generate each secret with `openssl rand -hex 24` and set it with `gh secret set <NAME> --env production`.

| Secret | Used by |
|---|---|
| `POSTGRES_PASSWORD` | Postgres owner `alinha` (Flyway migrations) |
| `ALINHA_APP_DB_PASSWORD` | runtime role `alinha_app` |
| `KEYCLOAK_DB_PASSWORD` | Keycloak's database `keycloak` |
| `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` | Keycloak bootstrap admin (console not public: `ssh -L 8180:...`, see below) |
| `BFF_CLIENT_SECRET` | client `bff` in the realm and the BFF |
| `KEYCLOAK_PROVISIONER_SECRET` | client `alinha-provisioner` in the realm and the app (through OpenBao) |
| `BAO_APP_TOKEN` | the app's OpenBao token |
| `BAO_SEED_TOKEN` | the `openbao-seed` job's token: can only write `secret/alinha/{app,ai-service}` |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | the SMTP relay's login (from the mail provider, not generated) |

The OpenBao **root token is not a secret here**: it stays with the operator (see OpenBao below).

Variables: `ALINHA_PUBLIC_URL=https://pei-alinha.duckdns.org`, `PROXY_NETWORK` (the reverse proxy's Docker network),
`TRUSTED_PROXY_CIDR` (that network's subnet, or the proxy's own `/32`), and the SMTP relay: `SMTP_HOST`, `SMTP_PORT`
(587, STARTTLS) and `SMTP_FROM` (the sender address, e.g. `no-reply@<domain>`). Keycloak and the app always
authenticate and require STARTTLS.

Compose refuses to start when any of them is missing (`${VAR:?}`), so an empty secret can never become a
placeholder in the imported realm.

## Things to know

- **Realm:** `deploy/Dockerfile.keycloak` builds the production realm from `deploy/keycloak/alinha-realm.json` with
  `scripts/gen-prod-realm.mjs`. `--import-realm` only imports a realm that does not exist yet: after the first
  deploy, realm changes must be made in the admin console (or the `keycloak` database reset).
- **OpenBao** is a real server with its data on the `openbao-data` volume (`deploy/openbao/server.hcl`). It is
  healthy only when unsealed. The secrets engine, policies and tokens are set up by hand with the root token
  (`deploy/openbao/bootstrap.sh`). On every deploy the `openbao-seed` job only writes the platform secrets
  (`deploy/openbao/secrets.sh`) with the narrow `BAO_SEED_TOKEN`, and the app waits for it. Staging keeps dev mode
  but seeds with the same narrow token; `deploy/openbao/test-seed.sh` (CI) runs the real server path. See **OpenBao** below.
- **Emails** (Keycloak's verification and password reset, the notifications plugin) go through the SMTP relay in
  `SMTP_*`. Staging sends them to Mailpit instead (http://localhost:8026). Registration and password reset are
  open to anyone, so `web` rate limits `/kc/realms/alinha/login-actions/` per client address (10 a minute, burst
  10, then 429): one address cannot make the relay send mail in bulk. Check the relay's own quota as well.
- **Keycloak admin console:** not routed by `web`. Use
  `ssh -L 8180:172.31.0.X:8080 ...` with the container's address (`docker inspect alinha-prod-keycloak-1`), then
  http://localhost:8180/kc/admin.
- **`pnpm-lock.yaml` follows the plugin pointers.** The root lockfile has an importer for every
  `plugins/*/frontend`, and CI and the `web`/`keycloak` images install with `--frozen-lockfile`. A pointer update
  that changes a plugin's `package.json` must commit a regenerated `pnpm-lock.yaml` with it (see `CLAUDE.md`), or
  the release cannot build.
- **Memory:** limits add up to about 3 GB (`mem_limit` in the compose file); the VM has about 4.8 GB free.

## One-time setup of the VM

1. Runner: repo → Settings → Actions → Runners → New self-hosted runner (Linux x64). On the VM:
   ```sh
   mkdir ~/actions-runner-alinha && cd ~/actions-runner-alinha
   # download and extract the runner as GitHub shows, then:
   ./config.sh --url https://github.com/PEI-aLinha/alinha --token <TOKEN> --labels prod --name alinha-prod --unattended
   sudo ./svc.sh install ubuntu && sudo ./svc.sh start
   ```
2. GitHub environment `production`: required reviewers, deployment branches = `main`, secrets and variable above.
3. **Lock the runner to `main`'s workflows.** The `if: github.ref == 'refs/heads/main'` and `environment:` lines are
   in the workflow files, so a run dispatched from another branch uses that branch's copy, which could drop them
   and run anything on the VM (the runner has Docker: root-equivalent). Put the runner in its own runner group
   (organisation → Settings → Actions → Runner groups), with access for this repository only and **Selected
   workflows** limited to `PEI-aLinha/alinha/.github/workflows/{deploy,logs,clean}.yml@refs/heads/main`.
4. **Protect the release tags:** repository → Settings → Rules → a tag ruleset for `v*` that blocks updates and
   deletions, so a deployed tag cannot be moved. `deploy.yml` also checks that the tag is on `main`.
5. Reverse proxy: in the `pei-alinha.duckdns.org` block, `proxy_set_header X-Forwarded-For $remote_addr;`
   (back up the file first), then test and reload that Nginx (`nginx -t && nginx -s reload`).
6. Retire the static prototype only when the first deploy runs:
   `docker inspect alinha-web > ~/backups/alinha-web-prototype.json && docker rm -f alinha-web`.
   `/var/www/alinha` stays; recreating that container (same mounts, network `PROXY_NETWORK`, alias
   `alinha-web`) brings the prototype back.

## Trying a release locally (staging)

```sh
make staging-env     # deploy/.env.staging with random secrets
make staging-build   # the production images, built from this checkout
make staging         # http://localhost:8088
make staging-clean   # stop and delete the volumes
```

Staging has the four test personas of `deploy/keycloak/README.md`, with the password `ALINHA_TEST_USER_PASSWORD`
from `deploy/.env.staging`. Production has no test users: its realm only keeps the provisioner's service account.

## OpenBao

Production OpenBao starts **sealed** after every container restart or VM reboot, and stays unhealthy until it is
unsealed. The app waits for it. The **root token is only used by hand**, never by a workflow: setting OpenBao up
(`deploy/openbao/bootstrap.sh`: secrets engine, policies, tokens) needs it, while each deploy's `openbao-seed` only
writes the platform secrets with `BAO_SEED_TOKEN`. Run every command below on the VM.

**Once, before the first deploy that uses it** (the deploy fails waiting for `openbao`, which is expected):

```sh
docker exec alinha-prod-openbao-1 bao operator init -key-shares=3 -key-threshold=2
```

It prints 3 unseal keys and the root token, once. Give one unseal key to each of three team members and keep them
out of the VM and out of the repo. Then unseal and set OpenBao up. `read -s` keeps the tokens out of the shell
history: paste the root token, then the values of the `BAO_APP_TOKEN` and `BAO_SEED_TOKEN` secrets.

```sh
docker exec -it alinha-prod-openbao-1 bao operator unseal   # asks for a key; repeat with a second key
read -rs ROOT; read -rs APP; read -rs SEED
docker exec -e BAO_TOKEN="$ROOT" -e ALINHA_BAO_APP_TOKEN="$APP" -e ALINHA_BAO_SEED_TOKEN="$SEED" \
  alinha-prod-openbao-1 sh /init/bootstrap.sh
docker exec -e BAO_TOKEN="$ROOT" alinha-prod-openbao-1 bao token revoke -self   # the root token is no longer needed
unset ROOT APP SEED
```

Run **Deploy to Production VM** again. `openbao-seed` writes the secrets.

**Changing a policy file** (`deploy/openbao/*.hcl`) or a token: create a root token for the occasion with
`bao operator generate-root` (two key holders), run `bootstrap.sh` as above, then revoke it.

**After a VM reboot or an OpenBao restart:** run `bao operator unseal` twice (two key holders). Until then the app
cannot read its secrets: `restart: always` keeps restarting it, and it comes up by itself once OpenBao is unsealed.
A deploy before the unseal fails waiting for `openbao`: if a deploy times out, check
`docker exec alinha-prod-openbao-1 bao status` (`Sealed true`) first.

**Token expiry:** `BAO_APP_TOKEN` and `BAO_SEED_TOKEN` are periodic tokens (768h, 32 days), renewed by the OpenBao
healthcheck and by every seed while the stack runs. If the stack is down for more than 32 days they expire: run
`bootstrap.sh` again as above (it recreates them with the same values).

**`secret/alinha/app` belongs to the deploy.** `openbao-seed` writes it with `bao kv put`, which replaces the whole
secret on every deploy, so a key added there by hand is lost on the next one. Add new platform secrets to
`deploy/openbao/secrets.sh` (and the workflow) instead.

**Losing the volume or the unseal keys loses every secret**, including the per-tenant provider credentials
(`TenantSecretStore`): back up the `alinha-prod_openbao-data` volume with the database.

## Rotating a secret

Changing a GitHub secret and deploying is enough only for what is read on every start: the values that
`openbao-seed` writes into OpenBao (`POSTGRES_PASSWORD` and `ALINHA_APP_DB_PASSWORD` as the app sees them,
`KEYCLOAK_PROVISIONER_SECRET`, `SMTP_PASSWORD`), and everything passed to a container's environment. Keycloak's realm
is imported only once and Postgres roles are created only on a fresh volume, so for these **change the running system
first, then the GitHub secret, then deploy**:

| Secret | Change it first in |
|---|---|
| `BFF_CLIENT_SECRET`, `KEYCLOAK_PROVISIONER_SECRET` | Keycloak, client `bff` / `alinha-provisioner` → Credentials (admin console through the SSH tunnel) |
| `SMTP_*` | Keycloak, realm `alinha` → Realm settings → Email (the app reads the new values on deploy) |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak, realm `master` → the admin user (the env value is only used on the very first start) |
| `POSTGRES_PASSWORD`, `ALINHA_APP_DB_PASSWORD`, `KEYCLOAK_DB_PASSWORD` | `docker exec -it alinha-prod-postgres-1 psql -U alinha -c '\password <role>'` with role `alinha`, `alinha_app` or `keycloak` |
| `BAO_APP_TOKEN`, `BAO_SEED_TOKEN` | OpenBao: revoke the old token and run `bootstrap.sh` with the new one (see OpenBao) |

`\password` asks for the new value, so it never appears in the shell history or the process list.
