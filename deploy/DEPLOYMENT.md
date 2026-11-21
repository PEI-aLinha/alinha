# Deployment (NEI VM)

The production stack runs on the NEI VM (`ubuntu@129.159.1.38`) and answers at https://pei-alinha.duckdns.org.
The flow is the one the NEI gamification system uses:

```text
gh release create vX.Y.Z  ──►  publish.yml (GitHub runners)  ──►  ghcr.io/pei-alinha/alinha/<image>:X.Y.Z
                                                                          │
Actions → "Deploy into NEI VM" → Run  ──►  deploy.yml (self-hosted runner on the VM)  ──►  docker compose up
```

| Piece | Where |
|---|---|
| Images | `deploy/Dockerfile.backend` (app, bff), `Dockerfile.web`, `Dockerfile.keycloak`, `workers/job_consumer/Dockerfile` |
| Stack | `deploy/docker-compose.prod.yml`, compose project `alinha-prod` |
| Secrets | GitHub environment `production` (required reviewers, `main` only). Nothing on the VM's disk. |
| Runner | `~/actions-runner-alinha` on the VM, labels `self-hosted,prod`, systemd service as `ubuntu` |
| Top proxy | `~/Platform-rally-extension/deploy/nginx.conf` (container `nei-rally-dev-proxy-1`), TLS for `pei-alinha.duckdns.org` |

## How a request gets in

```text
browser ─► top proxy (TLS, nei-rally-dev_rally) ─► web (alias alinha-web)
                                                    ├─ /                       shell (static)
                                                    ├─ /api/ /oauth2/ /login/oauth2/ /auth/ ─► bff ─► app
                                                    └─ /kc/  ─► keycloak   (/kc/admin is 404)
```

- The top proxy only knows the alias `alinha-web` on the network `nei-rally-dev_rally`; the `web` service joins that
  network with that alias, so the proxy needs no change when the stack is redeployed.
- The top proxy must send `X-Forwarded-For $remote_addr` (not `$proxy_add_x_forwarded_for`) in the
  `pei-alinha.duckdns.org` block, or a client can choose the address the BFF's rate limiter sees.
- `web` trusts `X-Forwarded-For` only from `TRUSTED_PROXY_CIDR` (the proxy's network, 172.25.0.0/16) and passes just
  the client address on. The app trusts it only from the BFF's fixed address (`172.31.0.10`).
- Keycloak runs under `/kc` with `KC_HOSTNAME=https://pei-alinha.duckdns.org/kc`, so tokens carry the public issuer.
  The BFF and the app fetch tokens and keys from `http://keycloak:8080/kc/...` inside the network.

## Release and deploy

1. Merge to `main` with CI green.
2. `gh release create vX.Y.Z --generate-notes` (the tag triggers `publish.yml`; wait for the 5 images).
3. Actions → **Deploy into NEI VM** → Run workflow on `main`. Empty `image_tag` = latest release. A reviewer approves
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
| `ALINHA_TEST_USER_PASSWORD` | the four test personas of `deploy/keycloak/README.md` |
| `BAO_ROOT_TOKEN`, `BAO_APP_TOKEN` | OpenBao root token and the app's token |

Variable: `ALINHA_PUBLIC_URL=https://pei-alinha.duckdns.org`. Optional: `PROXY_NETWORK`, `TRUSTED_PROXY_CIDR`.

Compose refuses to start when any of them is missing (`${VAR:?}`), so an empty secret can never become a
placeholder in the imported realm.

## Things to know

- **Realm:** `deploy/Dockerfile.keycloak` builds the production realm from `deploy/keycloak/alinha-realm.json` with
  `scripts/gen-prod-realm.mjs`. `--import-realm` only imports a realm that does not exist yet: after the first
  deploy, realm changes must be made in the admin console (or the `keycloak` database reset).
- **OpenBao** runs in dev mode (in memory). Its container seeds the secrets every time it starts and is healthy only
  after that; the app waits for it. Persistent storage is a follow-up.
- **Emails** (verification, password reset) go to Mailpit: `ssh -L 8026:127.0.0.1:8026 ubuntu@129.159.1.38`, then
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
   ./config.sh --url https://github.com/PEI-aLinha/alinha --token <TOKEN> --labels prod --name nei-vm-alinha --unattended
   sudo ./svc.sh install ubuntu && sudo ./svc.sh start
   ```
2. GitHub environment `production`: required reviewers, deployment branches = `main`, secrets and variable above.
3. Top proxy: in the `pei-alinha.duckdns.org` block, `proxy_set_header X-Forwarded-For $remote_addr;`
   (back up the file as `nginx.conf.before-alinha-stack-<timestamp>`), then
   `docker exec nei-rally-dev-proxy-1 nginx -t && docker exec nei-rally-dev-proxy-1 nginx -s reload`.
4. Retire the static prototype only when the first deploy runs:
   `docker inspect alinha-web > ~/backups/alinha-web-prototype.json && docker rm -f alinha-web`.
   `/var/www/alinha` stays; recreating that container (same mounts, network `nei-rally-dev_rally`, alias
   `alinha-web`) brings the prototype back.

## Trying a release locally (staging)

```sh
make staging-env     # deploy/.env.staging with random secrets
make staging-build   # the production images, built from this checkout
make staging         # http://localhost:8088
make staging-clean   # stop and delete the volumes
```

The test users' password is `ALINHA_TEST_USER_PASSWORD` in `deploy/.env.staging`.
