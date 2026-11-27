# Placeholder values for every ${VAR:?} in docker-compose.prod.yml and docker-compose.staging.yml, so the CI tests
# (test-compose.sh, openbao/test-seed.sh) can interpolate the files. Never real secrets. Sourced, not run.
export IMAGE_TAG=0.0.0
export ALINHA_PUBLIC_URL=https://alinha.test
export PROXY_NETWORK=alinha-ci-proxy
export TRUSTED_PROXY_CIDR=127.0.0.1/32
# Not the production default (172.31.0.0/24), so a test never clashes with a stack running on the same host.
export ALINHA_SUBNET=10.232.0.0/24
export BFF_IP=10.232.0.10
export ALINHA_TRUSTED_PROXIES='10\.232\.0\.10'
for v in POSTGRES_PASSWORD ALINHA_APP_DB_PASSWORD KEYCLOAK_DB_PASSWORD KEYCLOAK_ADMIN KEYCLOAK_ADMIN_PASSWORD \
         BFF_CLIENT_SECRET KEYCLOAK_PROVISIONER_SECRET BAO_APP_TOKEN BAO_SEED_TOKEN BAO_WORKER_TOKEN BAO_ROOT_TOKEN \
         ALINHA_TEST_USER_PASSWORD SMTP_HOST SMTP_PORT SMTP_FROM SMTP_USERNAME SMTP_PASSWORD; do
  export "$v=ci-placeholder-$(echo "$v" | tr 'A-Z_' 'a-z-')"
done
