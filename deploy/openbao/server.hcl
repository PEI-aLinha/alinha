# Production OpenBao (docker-compose.prod.yml): a real server with its data on the openbao-data volume, so secrets
# (platform secrets, per-tenant provider credentials stored through TenantSecretStore) survive restarts.
# It starts sealed: see "OpenBao" in deploy/DEPLOYMENT.md for the one-time init and the unseal after a reboot.

storage "file" {
  path = "/openbao/file"
}

# Plain HTTP on the internal compose network only: port 8200 is not published and only the app and the seed job
# reach it. Never publish this port.
listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = true
}

api_addr = "http://openbao:8200"
ui       = false
