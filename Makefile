SHELL := /bin/sh

COMPOSE := docker compose
COMPOSE_OBSERVABILITY := docker compose -f docker-compose.yml -f docker-compose.observability.yml

.DEFAULT_GOAL := help

.PHONY: help
help: ## Show available commands.
	@awk 'BEGIN {FS = ":.*##"; printf "Usage: make <target>\n\nTargets:\n"} /^[a-zA-Z0-9_.-]+:.*##/ {printf "  %-24s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.PHONY: test
test: test-bot test-worker ## Run bot-service and worker-service tests.

.PHONY: test-bot
test-bot: ## Run bot-service tests.
	cd bot-service && ./gradlew clean test

.PHONY: test-worker
test-worker: ## Run worker-service tests.
	cd worker-service && pytest

.PHONY: compose-config
compose-config: ## Validate base Docker Compose config.
	$(COMPOSE) config

.PHONY: compose-build
compose-build: ## Build base Docker Compose images.
	$(COMPOSE) build

.PHONY: up
up: ## Start the base stack.
	$(COMPOSE) up --build

.PHONY: up-detached
up-detached: ## Start the base stack in detached mode.
	$(COMPOSE) up --build -d

.PHONY: down
down: ## Stop the base stack.
	$(COMPOSE) down

.PHONY: obs-config
obs-config: ## Validate Docker Compose config with observability override.
	$(COMPOSE_OBSERVABILITY) config

.PHONY: obs-up
obs-up: ## Start the stack with Prometheus and Grafana.
	$(COMPOSE_OBSERVABILITY) up --build

.PHONY: obs-up-detached
obs-up-detached: ## Start the observability stack in detached mode.
	$(COMPOSE_OBSERVABILITY) up --build -d

.PHONY: obs-down
obs-down: ## Stop the observability stack.
	$(COMPOSE_OBSERVABILITY) down

.PHONY: health
health: ## Check bot and worker health endpoints.
	curl -fsS http://localhost:8080/actuator/health
	curl -fsS http://localhost:9091/health

.PHONY: metrics
metrics: ## Check Link2Action metrics endpoints.
	curl -fsS http://localhost:8080/actuator/prometheus | grep link2action
	curl -fsS http://localhost:9091/metrics | grep link2action

.PHONY: prometheus-targets
prometheus-targets: ## Show Prometheus target health.
	curl -fsS http://localhost:9090/api/v1/targets

.PHONY: clean
clean: ## Remove local build artifacts.
	rm -rf bot-service/build worker-service/.pytest_cache .pytest_cache
	find worker-service -type d -name __pycache__ -prune -exec rm -rf {} +
