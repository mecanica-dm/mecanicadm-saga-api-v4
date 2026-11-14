#!/usr/bin/env bash
#
# Aplica a topologia do RabbitMQ (exchanges, filas e bindings) descrita em um
# arquivo de definitions no vhost configurado, recurso por recurso.
#
# Por que não usar `POST /api/definitions`: o endpoint de import exige a tag
# "administrator" no RabbitMQ 4.x, e o plano compartilhado do CloudAMQP entrega
# um usuário apenas com "management"/"policymaker" (a própria CloudAMQP
# documenta a importação de definitions como exclusiva de instâncias
# dedicadas). Os endpoints por recurso usam apenas as permissões normais de
# vhost (configure/write/read), que o usuário da aplicação possui.
#
# Uso:
#   RABBITMQ_HOST=host RABBITMQ_USER=user RABBITMQ_PASSWORD=pass \
#   RABBITMQ_VHOST=vhost \
#     devops/scripts/rabbitmq-apply-topology.sh messaging/rabbitmq-definitions.json
#
# A operação é idempotente: PUT responde 201 ao criar e 204 quando o recurso já
# existe com as mesmas propriedades; criar um binding repetido é no-op. Nada é
# removido, então recursos criados manualmente ou por outros deploys no mesmo
# vhost (ex.: as filas do workorder e do finance) ficam intactos.

set -euo pipefail

DEFINITIONS="${1:?uso: $0 <definitions.json>}"

: "${RABBITMQ_HOST:?RABBITMQ_HOST não definido}"
: "${RABBITMQ_USER:?RABBITMQ_USER não definido}"
: "${RABBITMQ_PASSWORD:?RABBITMQ_PASSWORD não definido}"
: "${RABBITMQ_VHOST:?RABBITMQ_VHOST não definido}"

command -v jq >/dev/null 2>&1 || {
  echo "::error::jq não encontrado no PATH." >&2
  exit 1
}

[ -f "$DEFINITIONS" ] || {
  echo "::error::Arquivo de definitions não encontrado: $DEFINITIONS" >&2
  exit 1
}

BASE_URL="https://${RABBITMQ_HOST}/api"
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

# Os nomes entram na URL, então precisam ser percent-encoded (o vhost "/" vira
# %2F; no plano compartilhado o vhost é um nome simples, mas encode não custa).
enc() {
  printf '%s' "$1" | jq -sRr @uri
}

VHOST_ENC="$(enc "$RABBITMQ_VHOST")"

# request METHOD URL [JSON]
#
# Aceita 200/201/204 (criado, atualizado sem mudanças, OK). Qualquer outro
# status aborta com o corpo da resposta — inclusive 400 PRECONDITION_FAILED,
# que indica fila/exchange já existente com propriedades diferentes (drift).
request() {
  local method="$1" url="$2" payload="${3-}" status

  if [ -n "$payload" ]; then
    status="$(curl -sS -o "$BODY_FILE" -w '%{http_code}' \
      --retry 3 --retry-delay 5 --retry-connrefused --max-time 60 \
      --user "${RABBITMQ_USER}:${RABBITMQ_PASSWORD}" \
      -H 'content-type: application/json' \
      -X "$method" --data "$payload" "$url")"
  else
    status="$(curl -sS -o "$BODY_FILE" -w '%{http_code}' \
      --retry 3 --retry-delay 5 --retry-connrefused --max-time 60 \
      --user "${RABBITMQ_USER}:${RABBITMQ_PASSWORD}" \
      -X "$method" "$url")"
  fi

  case "$status" in
    200|201|204)
      return 0
      ;;
    *)
      echo "::error::Falha em $method $url (HTTP $status): $(cat "$BODY_FILE")" >&2
      exit 1
      ;;
  esac
}

# Exchanges antes das filas: as filas declaram x-dead-letter-exchange e não
# queremos depender da ordem de criação. Bindings por último, pois precisam das
# duas pontas.
while IFS= read -r resource; do
  name="$(jq -r '.name' <<<"$resource")"
  payload="$(jq -c '{
    type,
    durable: (.durable // true),
    auto_delete: (.auto_delete // false),
    internal: (.internal // false),
    arguments: (.arguments // {})
  }' <<<"$resource")"

  request PUT "$BASE_URL/exchanges/$VHOST_ENC/$(enc "$name")" "$payload"
  echo "exchange aplicado: $name"
done < <(jq -c '.exchanges[]?' "$DEFINITIONS")

while IFS= read -r resource; do
  name="$(jq -r '.name' <<<"$resource")"
  payload="$(jq -c '{
    durable: (.durable // true),
    auto_delete: (.auto_delete // false),
    exclusive: (.exclusive // false),
    arguments: (.arguments // {})
  }' <<<"$resource")"

  request PUT "$BASE_URL/queues/$VHOST_ENC/$(enc "$name")" "$payload"
  echo "fila aplicada: $name"
done < <(jq -c '.queues[]?' "$DEFINITIONS")

while IFS= read -r resource; do
  source="$(jq -r '.source' <<<"$resource")"
  destination="$(jq -r '.destination' <<<"$resource")"
  destination_type="$(jq -r '.destination_type' <<<"$resource")"

  case "$destination_type" in
    queue) type_segment="q" ;;
    exchange) type_segment="e" ;;
    *)
      echo "::error::destination_type desconhecido: $destination_type" >&2
      exit 1
      ;;
  esac

  payload="$(jq -c '{
    routing_key: (.routing_key // ""),
    arguments: (.arguments // {})
  }' <<<"$resource")"

  request POST \
    "$BASE_URL/bindings/$VHOST_ENC/e/$(enc "$source")/$type_segment/$(enc "$destination")" \
    "$payload"
  echo "binding aplicado: $source -> $destination"
done < <(jq -c '.bindings[]?' "$DEFINITIONS")

# Confere que toda fila declarada no arquivo existe de fato no vhost: um PUT
# que "passou" mas não criou nada (ex.: permissão parcial) não deve passar
# silenciosamente para o deploy.
request GET "$BASE_URL/queues/$VHOST_ENC"

# A comparação é feita toda em jq (em vez de grep linha a linha) para não
# depender de final de linha: builds do jq para Windows podem emitir CRLF, e um
# "grep -x budget" não casa com "budget\r". -j não emite o \n final, então não
# há \r para limpar de nenhum lado.
MISSING_QUEUES="$(jq -j -n \
  --slurpfile defs "$DEFINITIONS" \
  --slurpfile broker "$BODY_FILE" \
  '([$defs[0].queues[]?.name]) - ([$broker[0][].name]) | join(" ")')"

if [ -n "$MISSING_QUEUES" ]; then
  echo "::error::Filas ausentes após o apply: $MISSING_QUEUES" >&2
  exit 1
fi

echo "Topologia aplicada no vhost ${RABBITMQ_VHOST} de ${RABBITMQ_HOST}."
