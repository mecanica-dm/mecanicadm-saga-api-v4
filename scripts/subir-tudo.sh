#!/usr/bin/env bash
# Sobe o sistema inteiro (broker, Saga API, Workorder API e Finance API) na ordem certa e espera ficar saudavel.
# Uso: ./scripts/subir-tudo.sh
#
# Requisito: as tres pastas lado a lado (mecanicadm-saga-api-v4, mecanicadm-workorder-api-v4 e mecanicadm-finance-api-v4).
# Cada servico continua podendo ser subido sozinho com `docker compose up -d --build` na sua propria pasta.
set -euo pipefail

SAGA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(dirname "$SAGA_DIR")"
SAGA_PORT="${SAGA_PORT:-8081}"
WORKORDER_PORT="${WORKORDER_PORT:-8080}"
FINANCE_PORT="${FINANCE_PORT:-8082}"

for pasta in mecanicadm-workorder-api-v4 mecanicadm-finance-api-v4; do
  if [[ ! -d "$ROOT/$pasta" ]]; then
    echo "Pasta nao encontrada: $ROOT/$pasta" >&2
    echo "Clone os tres repositorios lado a lado, na mesma pasta." >&2
    exit 1
  fi
done

sobe() {
  local dir="$1"; shift
  echo ">>> $(basename "$dir"): docker compose $* up -d --build"
  (cd "$dir" && docker compose "$@" up -d --build)
}

espera() {
  local nome="$1" url="$2"
  printf "Aguardando %s" "$nome"
  for _ in $(seq 1 90); do
    if curl -fsS "$url" 2>/dev/null | grep -q '"status":"UP"'; then echo " ok"; return 0; fi
    printf "."; sleep 3
  done
  echo " TEMPO ESGOTADO (veja: docker compose logs api, na pasta do servico)"; return 1
}

# 1) Saga API + broker (o broker e compartilhado pelos tres servicos e precisa subir primeiro)
sobe "$SAGA_DIR" --profile broker
espera "Saga API" "http://localhost:${SAGA_PORT}/api/actuator/health/liveness"
# 2) Workorder API
sobe "$ROOT/mecanicadm-workorder-api-v4"
espera "Workorder API" "http://localhost:${WORKORDER_PORT}/api/actuator/health/liveness"
# 3) Finance API
sobe "$ROOT/mecanicadm-finance-api-v4"
espera "Finance API" "http://localhost:${FINANCE_PORT}/api/actuator/health/liveness"

cat <<EOF

Tudo no ar:
  Workorder API  http://localhost:${WORKORDER_PORT}/api/swagger-ui.html   (login do seed: admin@mecanicadm.com / Senha123)
  Saga API       http://localhost:${SAGA_PORT}/api/swagger-ui.html
  Finance API    http://localhost:${FINANCE_PORT}/api/swagger-ui.html
  RabbitMQ       http://localhost:15672   (guest / guest)
  Mailpit        http://localhost:8025    (e-mails de orcamento)
EOF
