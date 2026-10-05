#!/usr/bin/env bash
set -euo pipefail

# Управление ручками /incidents демо-приложения.
#
# Примеры:
#   scripts/incidents.sh list
#   scripts/incidents.sh start cpu
#   scripts/incidents.sh status cpu
#   scripts/incidents.sh fix cpu
#   scripts/incidents.sh stop cpu
#
# Переопределить адрес приложения:
#   APP_URL=http://localhost:8080 scripts/incidents.sh list

BASE_URL="${APP_URL:-http://localhost:8080}"
ACTION="${1:-}"
TYPE="${2:-}"

TYPES="cpu allocation gc memory-leak native-memory deadlock threads blocking-io db-pool"

usage() {
  cat <<EOF
Usage: $0 <list|status|start|fix|stop> [type]

  list              список инцидентов и их статусы
  status <type>     статус одного инцидента
  start  <type>     активировать проблему
  fix    <type>     применить демо-фикс
  stop   <type>     сбросить инцидент

Types: ${TYPES}
EOF
}

pretty() {
  if command -v jq >/dev/null 2>&1; then
    jq .
  else
    python3 -m json.tool
  fi
}

case "$ACTION" in
  list)
    curl -s "${BASE_URL}/incidents" | pretty
    ;;
  status)
    [ -n "$TYPE" ] || { usage; exit 1; }
    curl -s "${BASE_URL}/incidents/${TYPE}/status" | pretty
    ;;
  start)
    [ -n "$TYPE" ] || { usage; exit 1; }
    curl -s -X POST "${BASE_URL}/incidents/${TYPE}/start" | pretty
    ;;
  fix)
    [ -n "$TYPE" ] || { usage; exit 1; }
    curl -s -X POST "${BASE_URL}/incidents/${TYPE}/fix" | pretty
    ;;
  stop)
    [ -n "$TYPE" ] || { usage; exit 1; }
    curl -s -X POST "${BASE_URL}/incidents/${TYPE}/stop" | pretty
    ;;
  *)
    usage
    exit 1
    ;;
esac
