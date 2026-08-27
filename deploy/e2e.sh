#!/usr/bin/env bash
# End-to-end checks against a running packaged stack.
#
# Everything here is something no local dev run can prove, because dev has no reverse proxy: Vite
# supplies the single origin, and the webhook tunnel points straight at the gateway.
#
#   ./deploy/e2e.sh http://localhost:34700 http://localhost:34767
#
# Requires, in the environment: the three SPIRE_OIDC_*_SECRET values and DEV_VIEWER_PASSWORD /
# DEV_OPERATOR_PASSWORD from the realm. Source deploy/.env first.
set -uo pipefail

BASE="${1:?usage: e2e.sh <base-url> <keycloak-url>}"
KC="${2:?usage: e2e.sh <base-url> <keycloak-url>}"
FAILED=0

pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILED=1; }

# check <name> <expected> <curl args...>
# The login redirect is only useful if the redirect_uri inside it is reachable by the browser and
# registered with the realm. Asserting the 302 alone passed for months while every service sent its
# own container address (http://orchestrator:8080/...), because nginx locations that set any
# proxy_set_header stop inheriting the ones on the server block and Host falls back to $proxy_host.
check_redirect_origin() {
    local name="$1" url="$2" want="$3"
    local location redirect
    location="$(curl -s -o /dev/null -D - --max-time 20 "$url" | tr -d "\r" | sed -n "s/^[Ll]ocation: //p")"
    redirect="$(printf "%s" "$location" | sed -n "s/.*redirect_uri=\([^&]*\).*/\1/p" \
                 | sed "s/%3A/:/g; s/%2F/\//g")"
    case "$redirect" in
        "$want"*) pass "$name ($redirect)" ;;
        "")       fail "$name — no redirect_uri in the login redirect" ;;
        *)        fail "$name — redirect_uri is $redirect, not on $want" ;;
    esac
}

check() {
    local name="$1" expected="$2"; shift 2
    local actual
    actual="$(curl -s -o /dev/null --max-time 20 -w '%{http_code}' "$@")"
    if [ "$actual" = "$expected" ]; then
        pass "$name ($actual)"
    else
        fail "$name — expected $expected, got $actual"
    fi
}

# token <client-id> <client-secret> <username> <password>
token() {
    curl -s --max-time 20 -X POST "$KC/realms/spire/protocol/openid-connect/token" \
        -d grant_type=password -d "client_id=$1" -d "client_secret=$2" \
        -d "username=$3" -d "password=$4" \
        | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

echo "== static assets and SCM ingress =="
check "dashboard served"             200 "$BASE/"
check "proxy health"                 200 "$BASE/healthz"
check "unknown path serves the SPA"  200 "$BASE/no-such-route"
# The route that must NOT fall through. 404 is the GATEWAY answering: the routing key is not
# registered, so there is no per-repo secret to verify against. A 405 would mean the request reached
# the SPA fallback instead (nginx refuses POST to a static file) — every SCM delivery would fail and
# no review would ever start.
check "webhook reaches the gateway"  404 -X POST -H 'Content-Type: application/json' \
                                         -d '{"TEST":"unsigned"}' "$BASE/webhooks/github/TEST-key"

echo "== public and protected paths through the proxy =="
check "me is public"                 200 "$BASE/api/me"
check "health is public"             200 "$BASE/q/health"
check "api needs a session"          302 "$BASE/api/reviews"
check "gateway login endpoint"       302 "$BASE/gw/auth/login"
check "worker login endpoint"        302 "$BASE/wk/auth/login"
check_redirect_origin "api callback is on the public origin"     "$BASE/api/auth/login" "$BASE"
check_redirect_origin "gateway callback is on the public origin" "$BASE/gw/auth/login"  "$BASE"
check_redirect_origin "worker callback is on the public origin"  "$BASE/wk/auth/login"  "$BASE"

echo "== tokens =="
API_V="$(token spire-orchestrator  "${SPIRE_OIDC_ORCHESTRATOR_SECRET:?required}" dev-viewer   "${DEV_VIEWER_PASSWORD:?required}")"
API_O="$(token spire-orchestrator  "${SPIRE_OIDC_ORCHESTRATOR_SECRET}"          dev-operator "${DEV_OPERATOR_PASSWORD:?required}")"
GW_V="$(token spire-gateway        "${SPIRE_OIDC_GATEWAY_SECRET:?required}"     dev-viewer   "${DEV_VIEWER_PASSWORD}")"
GW_O="$(token spire-gateway        "${SPIRE_OIDC_GATEWAY_SECRET}"               dev-operator "${DEV_OPERATOR_PASSWORD}")"
for pair in "API_V:$API_V" "API_O:$API_O" "GW_V:$GW_V" "GW_O:$GW_O"; do
    [ -n "${pair#*:}" ] || { fail "could not obtain ${pair%%:*}"; echo "e2e FAILED"; exit 1; }
done
pass "four tokens obtained"

echo "== roles through the proxy =="
check "viewer reads reviews"          200 -H "Authorization: Bearer $API_V" "$BASE/api/reviews"
check "viewer reads attention"        200 -H "Authorization: Bearer $API_V" "$BASE/api/attention"
check "viewer refused providers"      403 -H "Authorization: Bearer $API_V" "$BASE/api/providers"
check "viewer refused the dlq"        403 -H "Authorization: Bearer $API_V" "$BASE/api/dlq"
check "admin reads providers"         200 -H "Authorization: Bearer $API_O" "$BASE/api/providers"
check "viewer refused the registry"   403 -H "Authorization: Bearer $GW_V"  "$BASE/gw/webhook-repos"
check "admin reads the registry"      200 -H "Authorization: Bearer $GW_O"  "$BASE/gw/webhook-repos"
check "viewer reads gw attention"     200 -H "Authorization: Bearer $GW_V"  "$BASE/gw/webhook-repos/attention"

echo "== a token minted for one service is refused by another =="
# The bearer counterpart of ADR-022's per-path cookie scoping: each service is its own OIDC client
# with its own audience mapper, so a session or token for one cannot be replayed against another.
# Asserting it positively, because it is the property the whole prefix split exists to produce.
check "orchestrator token at the gateway" 401 -H "Authorization: Bearer $API_V" "$BASE/gw/webhook-repos"

echo "== websocket upgrade traverses nginx =="
# --max-time matters: a SUCCESSFUL upgrade leaves curl holding an open socket, so without it this
# check hangs CI rather than passing.
ws() {
    curl -s -o /dev/null --max-time 6 -w '%{http_code}' "$@" \
        -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
        -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' || true
}
upgraded="$(ws -H "Authorization: Bearer $API_V" "$BASE/api/ws/timeline")"
[ "$upgraded" = "101" ] && pass "timeline socket upgraded (101)" \
                        || fail "timeline socket — expected 101, got ${upgraded:-<no status>}"
refused="$(ws "$BASE/api/ws/timeline")"
[ "$refused" = "302" ] && pass "unauthenticated socket refused (302)" \
                       || fail "unauthenticated socket — expected 302, got ${refused:-<no status>}"

echo "== the gateway's privilege boundary =="
# The only check that tests what SECURITY.md actually cares about. Distinct usernames in the
# manifests and an existing role prove nothing about what that role can READ: an operator who fixes a
# permission error with a broad GRANT collapses the boundary with every piece of config still
# distinct.
COMPOSE="${SPIRE_COMPOSE_FILE:-$(dirname "$0")/compose.ghcr.yml}"
probe="$(docker compose -f "$COMPOSE" exec -T \
    -e PGPASSWORD="${GATEWAY_POSTGRES_PASSWORD:?required}" postgres \
    psql -U "${GATEWAY_POSTGRES_USER:?required}" -d "${POSTGRES_DB:?required}" \
    -tAc 'SELECT count(*) FROM orchestrator.review_status' 2>&1)"
if printf '%s' "$probe" | grep -qiE 'permission denied|does not exist'; then
    pass "gateway role cannot read the orchestrator schema"
else
    fail "gateway role READ the orchestrator schema: $probe"
fi

echo
if [ "$FAILED" -ne 0 ]; then
    echo "e2e FAILED"
    exit 1
fi
echo "e2e passed"
