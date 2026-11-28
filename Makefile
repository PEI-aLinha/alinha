.PHONY: help staging staging-env staging-build staging-down staging-clean staging-logs

STAGING_COMPOSE := docker compose --project-name alinha-staging -f deploy/docker-compose.prod.yml -f deploy/docker-compose.staging.yml --env-file deploy/.env.staging
STAGING_NETWORK := alinha-staging-proxy

help:
	@echo "Local development: see README.md (docker compose -f deploy/docker-compose.dev.yml up -d)."
	@echo ""
	@echo "Production: only .github/workflows/deploy.yml, logs.yml and clean.yml (see deploy/DEPLOYMENT.md)."
	@echo ""
	@echo "Staging (production images built from this checkout, http://localhost:8088):"
	@echo "  make staging-env   - Create deploy/.env.staging with random secrets"
	@echo "  make staging-build - Build the images"
	@echo "  make staging       - Start the stack"
	@echo "  make staging-down  - Stop the stack"
	@echo "  make staging-clean - Stop the stack and delete its volumes"
	@echo "  make staging-logs  - Follow the logs"

## --- Staging
staging-env:
	@if [ -f deploy/.env.staging ]; then echo "deploy/.env.staging already exists"; exit 0; fi; \
	{ \
	  echo "IMAGE_PREFIX=alinha-staging"; \
	  echo "IMAGE_TAG=local"; \
	  echo "ALINHA_PUBLIC_URL=http://localhost:8088"; \
	  echo "PROXY_NETWORK=$(STAGING_NETWORK)"; \
	  echo "TRUSTED_PROXY_CIDR=127.0.0.1/32"; \
	  echo "ALINHA_SUBNET=10.231.0.0/24"; \
	  echo "BFF_IP=10.231.0.10"; \
	  echo 'ALINHA_TRUSTED_PROXIES=10\.231\.0\.10'; \
	  echo "KEYCLOAK_ADMIN=admin"; \
	  echo "SMTP_HOST=mailpit"; \
	  echo "SMTP_PORT=1025"; \
	  echo "SMTP_FROM=no-reply@alinha.pt"; \
	  echo "SMTP_USERNAME=staging"; \
	  for v in POSTGRES_PASSWORD ALINHA_APP_DB_PASSWORD KEYCLOAK_DB_PASSWORD KEYCLOAK_ADMIN_PASSWORD \
	           BFF_CLIENT_SECRET KEYCLOAK_PROVISIONER_SECRET ALINHA_TEST_USER_PASSWORD BAO_ROOT_TOKEN BAO_APP_TOKEN \
	           BAO_SEED_TOKEN BAO_WORKER_TOKEN SMTP_PASSWORD; do \
	    echo "$$v=$$(openssl rand -hex 24)"; \
	  done; \
	} > deploy/.env.staging && chmod 600 deploy/.env.staging && echo "Created deploy/.env.staging"

staging-build: staging-env
	$(STAGING_COMPOSE) build

staging: staging-env
	@docker network inspect $(STAGING_NETWORK) >/dev/null 2>&1 || docker network create $(STAGING_NETWORK)
	$(STAGING_COMPOSE) up -d --remove-orphans --wait --wait-timeout 600

staging-down:
	$(STAGING_COMPOSE) down --remove-orphans

staging-clean:
	$(STAGING_COMPOSE) down --volumes --remove-orphans

staging-logs:
	$(STAGING_COMPOSE) logs -f
