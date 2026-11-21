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

Rolling back = running the deploy again with the previous version as `image_tag`.

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
| `BAO_ROOT_TOKEN` | OpenBao root token **printed by `bao operator init`** (see OpenBao below), used only by the seed job |
| `BAO_APP_TOKEN` | the app's OpenBao token (generated like the others) |

Variables: `ALINHA_PUBLIC_URL=https://pei-alinha.duckdns.org`, `PROXY_NETWORK` (the reverse proxy's Docker network)
and `TRUSTED_PROXY_CIDR` (that network's subnet, or the proxy's own `/32`).

Compose refuses to start when any of them is missing (`${VAR:?}`), so an empty secret can never become a
placeholder in the imported realm.

## Things to know

- **Realm:** `deploy/Dockerfile.keycloak` builds the production realm from `deploy/keycloak/alinha-realm.json` with
  `scripts/gen-prod-realm.mjs`. `--import-realm` only imports a realm that does not exist yet: after the first
  deploy, realm changes must be made in the admin console (or the `keycloak` database reset).
- **OpenBao** is a real server with its data on the `openbao-data` volume (`deploy/openbao/server.hcl`). It is
  healthy only when unsealed. On every deploy the `openbao-seed` job writes the secrets, policies and app token
  (`deploy/openbao/init.sh`) and the app waits for it. Staging keeps dev mode. See **OpenBao** below.
- **Emails** (verification, password reset) go to Mailpit: `ssh -L 8026:127.0.0.1:8026 <vm>`, then
  http://localhost:8026.
- **Keycloak admin console:** not routed by `web`. Use
  `ssh -L 8180:172.31.0.X:8080 ...` with the container's address (`docker inspect alinha-prod-keycloak-1`), then
  http://localhost:8180/kc/admin.
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
3. Reverse proxy: in the `pei-alinha.duckdns.org` block, `proxy_set_header X-Forwarded-For $remote_addr;`
   (back up the file first), then test and reload that Nginx (`nginx -t && nginx -s reload`).
4. Retire the static prototype only when the first deploy runs:
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
unsealed. The app waits for it. Run every command below on the VM.

**Once, before the first deploy that uses it** (the deploy fails waiting for `openbao`, which is expected):

```sh
docker exec alinha-prod-openbao-1 bao operator init -key-shares=3 -key-threshold=2
```

It prints 3 unseal keys and the root token, once. Give one unseal key to each of three team members and keep them
out of the VM and out of the repo. Then:

```sh
docker exec alinha-prod-openbao-1 bao operator unseal   # asks for a key; repeat with a second key
gh secret set BAO_ROOT_TOKEN --env production           # paste the root token from init
```

Run **Deploy to Production VM** again. `openbao-seed` enables `secret/` and writes everything.

**After a VM reboot or an OpenBao restart:** run `bao operator unseal` twice (two key holders), then re-run the deploy,
or wait for the app's `restart: always` to pick it up.

**Losing the volume or the unseal keys loses every secret**, including the per-tenant provider credentials
(`TenantSecretStore`): back up the `alinha-prod_openbao-data` volume with the database.

