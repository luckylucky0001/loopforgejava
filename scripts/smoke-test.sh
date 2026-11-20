#!/usr/bin/env bash
# End-to-end smoke test against a running LoopForge4J server:
# readiness -> auth -> catalog -> run the welcome SOP -> approve -> succeeded with artifacts.
#   scripts/smoke-test.sh http://localhost:8090 "$LOOPFORGE_API_TOKEN"
set -euo pipefail

BASE="${1:-http://localhost:8090}"
TOKEN="${2:?usage: smoke-test.sh BASE_URL TOKEN}"
API="$BASE/api/v1"
AUTH=(-H "Authorization: Bearer $TOKEN")

json() { python3 -c "import sys, json; d = json.load(sys.stdin); print($1)"; }
fail() { echo "SMOKE FAILED: $*" >&2; exit 1; }

for _ in $(seq 1 90); do
  curl -fsS "$BASE/actuator/health/readiness" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS "$BASE/actuator/health/readiness" >/dev/null || fail "server never became ready"

status=$(curl -s -o /dev/null -w '%{http_code}' "$API/workflows")
[ "$status" = 401 ] || fail "unauthenticated request returned $status, expected 401"

curl -fsS "${AUTH[@]}" "$API/workflows" | json "'welcome-workflow' in [w['id'] for w in d]" | grep -qx True \
  || fail "welcome-workflow missing from catalog"

run=$(curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"workflowId":"welcome-workflow","input":"smoke"}' "$API/runs" | json "d['id']")
echo "started $run"

wait_for() {
  for _ in $(seq 1 60); do
    current=$(curl -fsS "${AUTH[@]}" "$API/runs/$run" | json "d['status']")
    [ "$current" = "$1" ] && return 0
    sleep 1
  done
  fail "run $run is '$current', expected '$1'"
}

wait_for waiting
curl -fsS "${AUTH[@]}" -H 'Content-Type: application/json' -d '{"humanContext":"smoke approve"}' \
  -X POST "$API/runs/$run/nodes/approve/approve" >/dev/null
wait_for succeeded

count=$(curl -fsS "${AUTH[@]}" "$API/runs/$run/artifacts" | json "len(d)")
[ "$count" = 4 ] || fail "expected 4 artifacts, got $count"
echo "SMOKE OK: $run succeeded with $count artifacts"
