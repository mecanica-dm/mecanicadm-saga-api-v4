#!/usr/bin/env bash
# Derruba o sistema inteiro. Com --limpar, apaga tambem os volumes (bancos e broker) para comecar do zero.
# Uso: ./scripts/derrubar-tudo.sh [--limpar]
set -euo pipefail

SAGA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(dirname "$SAGA_DIR")"
EXTRA=()
if [[ "${1:-}" == "--limpar" ]]; then EXTRA=(-v); fi

# Ordem inversa da subida: o broker (na pasta da saga) e o ultimo a cair.
for pasta in mecanicadm-finance-api-v4 mecanicadm-workorder-api-v4; do
  if [[ -d "$ROOT/$pasta" ]]; then
    echo ">>> $pasta: docker compose down ${EXTRA[*]:-}"
    (cd "$ROOT/$pasta" && docker compose down ${EXTRA[@]+"${EXTRA[@]}"})
  fi
done
echo ">>> mecanicadm-saga-api-v4: docker compose --profile broker down ${EXTRA[*]:-}"
(cd "$SAGA_DIR" && docker compose --profile broker down ${EXTRA[@]+"${EXTRA[@]}"})
