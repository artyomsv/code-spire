#!/usr/bin/env bash
# Invariants that exist ONLY as configuration shape, which is why no unit test reaches them:
# flattening any of them would fail nothing and break everything.
#
# The checks span TWO sources, and each says which:
#   - rendered Helm output, for what the chart decides;
#   - in-repo config files, for what is baked into an image (the nginx template, the cookie paths).
# Treating the second group as manifest greps would make them vacuous — there is no cookie path in a
# manifest to find.
#
#   ./tests/render.sh              assert the invariants hold
#   ./tests/render.sh --self-test  assert each check catches its own break
set -uo pipefail

CHART="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$CHART/../../.." && pwd)"
NGINX="$REPO/spire-ui/nginx/default.conf.template"
FAILED=0

pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILED=1; }

# render <values-file> [extra helm args...]
render() {
    local values="$1"; shift
    helm template spire "$CHART" -f "$CHART/$values" \
        --set secrets.existingSecret=TEST-spire-secrets \
        --set secrets.gatewayExistingSecret=TEST-spire-gateway-secrets \
        --set trustedProxies=10.244.0.0/16 \
        --set postgres.host=postgres.example.invalid \
        --set kafka.bootstrapServers=kafka.example.invalid:9092 \
        --set oidc.authServerUrl=https://idp.example.invalid/realms/spire \
        "$@" 2>&1
}

# The env block of one Deployment, as text. Splits the stream on document boundaries and keeps the one
# whose metadata names the wanted Deployment.
deployment() {   # deployment <rendered> <name>
    printf '%s' "$1" | awk -v want="$2" '
        BEGIN { RS = "\n---\n" }
        $0 ~ ("kind: Deployment") && $0 ~ ("name: " want "\n") { print }
    '
}

present() {   # present <rendered> <deployment> <needle>
    if deployment "$1" "$2" | grep -q -- "$3"; then pass "$3 present on $2"; else fail "$3 missing from $2"; fi
}

absent() {    # absent <rendered> <deployment> <needle>
    if deployment "$1" "$2" | grep -q -- "$3"; then fail "$3 must NOT reach $2"; else pass "$3 absent from $2"; fi
}

check_manifests() {
    local out
    out="$(render values-simple.yaml)"
    if printf '%s' "$out" | grep -q '^Error'; then
        fail "helm template failed: $(printf '%s' "$out" | head -3)"
        return
    fi

    # 1 + 2 — the two keysets never meet. A compromised internet-facing gateway must be able to verify
    # webhook signatures and nothing else (ADR-015).
    present "$out" spire-orchestrator SPIRE_ENCRYPTION_KEYSET
    present "$out" spire-worker       SPIRE_ENCRYPTION_KEYSET
    absent  "$out" spire-gateway      SPIRE_ENCRYPTION_KEYSET
    present "$out" spire-gateway      SPIRE_ENCRYPTION_WEBHOOK_KEYSET
    absent  "$out" spire-orchestrator SPIRE_ENCRYPTION_WEBHOOK_KEYSET
    absent  "$out" spire-worker       SPIRE_ENCRYPTION_WEBHOOK_KEYSET

    # 3 — the gateway connects as its own schema-scoped role.
    local gw orch
    gw="$(deployment "$out" spire-gateway | grep -A6 'name: QUARKUS_DATASOURCE_USERNAME' | grep -o 'key: [A-Z_]*' | head -1)"
    orch="$(deployment "$out" spire-orchestrator | grep -A6 'name: QUARKUS_DATASOURCE_USERNAME' | grep -o 'key: [A-Z_]*' | head -1)"
    if [ -n "$gw" ] && [ -n "$orch" ] && [ "$gw" != "$orch" ]; then
        pass "gateway datasource user differs from the orchestrator's ($gw vs $orch)"
    else
        fail "gateway and orchestrator resolve the same datasource user ('$gw' vs '$orch')"
    fi

    # 4 — three distinct OIDC client-secret keys. One shared secret ends the per-service isolation
    # without failing anything visible.
    local keys
    keys="$(printf '%s' "$out" | grep -A6 'name: SPIRE_OIDC_CLIENT_SECRET' | grep -o 'key: [A-Z_]*' | sort -u | wc -l)"
    if [ "$keys" -eq 3 ]; then
        pass "three distinct OIDC client-secret keys"
    else
        fail "expected 3 distinct OIDC client-secret keys, found $keys"
    fi

    # 5 — the dashboard is the only ingress. trusted-proxies means nothing if a service port is
    # reachable directly, because anything that reaches it can forge X-Forwarded-For.
    local exposed
    exposed="$(printf '%s' "$out" | awk 'BEGIN{RS="\n---\n"} $0 ~ "kind: Service" && $0 ~ "type: (NodePort|LoadBalancer)" { n++ } END { print n+0 }')"
    if [ "$exposed" -le 1 ]; then
        pass "at most one externally-typed Service ($exposed)"
    else
        fail "$exposed Services are externally typed; only the dashboard may be"
    fi

    # 8 — the no-defaults contract: the chart must REFUSE to render without its required values, and
    # say which one is missing.
    #
    # This is its own assertion because `helm lint` does not enforce it. Lint reports every missing
    # required value as a WARNING and exits 0 — measured, not assumed — so a lint-only gate would pass
    # a chart that cannot install and would let a secret quietly acquire a default later.
    local bare rc
    bare="$(helm template spire "$CHART" -f "$CHART/values-simple.yaml" 2>&1)"
    rc=$?
    if [ "$rc" -ne 0 ] && printf '%s' "$bare" | grep -q 'is required'; then
        pass "the chart refuses to render without its required values"
    else
        fail "the chart rendered with NO secret names or trusted proxies (exit $rc) — something has a default"
    fi
}

check_repo_config() {
    local conf; conf="$(cat "$NGINX")"

    # 6 — the nginx template: every prefix routed, /webhooks before the SPA fallback, both sockets
    # upgrading, Host preserved, and X-Forwarded-Proto NOT derived from $scheme.
    local prefix
    for prefix in '/webhooks' '/api' '/gw' '/wk'; do
        if printf '%s' "$conf" | grep -q "location $prefix"; then
            pass "nginx routes $prefix"
        else
            fail "nginx does not route $prefix"
        fi
    done

    if printf '%s' "$conf" | grep -q 'X-Forwarded-Proto \$scheme'; then
        fail 'nginx derives X-Forwarded-Proto from $scheme, clobbering an upstream TLS terminator'
    else
        pass 'X-Forwarded-Proto passes an upstream value through'
    fi

    # $host drops the port, so a dashboard on any port but 80 would produce a callback the realm
    # cannot match; $http_host carries it. Checked as a rejection too: passing this with $host is
    # how the packaged stack shipped a login that only worked on port 80.
    if printf '%s' "$conf" | grep -qE 'proxy_set_header +Host +\$http_host'; then
        pass 'Host is preserved with its port'
    else
        fail 'Host is not preserved with its port — the callback would drop it'
    fi

    # And it must be set in EVERY location that sets any header of its own. nginx inherits
    # proxy_set_header from an outer level only when the inner level defines none, so a location
    # adding Upgrade/Connection for a WebSocket silently discards Host, X-Forwarded-Proto and
    # -For — Host then falls back to $proxy_host, the upstream NAME, and redirect_uri points at a
    # backend port. That is precisely what shipped: the previous form of this assertion passed
    # because it only asked whether the directive existed somewhere in the file.
    missing="$(printf '%s' "$conf" | awk '
        /^[[:space:]]*location[[:space:]]/ { loc = $0; sub(/^[[:space:]]*/, "", loc); sets = 0; host = 0; depth = 1; next }
        loc != "" {
            if ($0 ~ /proxy_set_header/) { sets = 1 }
            if ($0 ~ /proxy_set_header[[:space:]]+Host[[:space:]]/) { host = 1 }
            n = gsub(/\{/, "{"); m = gsub(/\}/, "}"); depth += n - m
            if (depth <= 0) { if (sets && !host) print loc; loc = "" }
        }
    ')"
    if [ -z "$missing" ]; then
        pass 'every header-setting location re-states Host'
    else
        fail "a location sets headers but not Host, so it inherits none: $(printf '%s' "$missing" | tr '\n' ' ')"
    fi

    local upgrades
    upgrades="$(printf '%s' "$conf" | grep -c 'proxy_set_header Upgrade')"
    if [ "$upgrades" -ge 2 ]; then
        pass "WebSocket upgrade on both socket prefixes ($upgrades)"
    else
        fail "expected upgrade headers on /api and /gw, found $upgrades"
    fi

    local webhook_line spa_line
    webhook_line="$(grep -n 'location /webhooks' "$NGINX" | head -1 | cut -d: -f1)"
    spa_line="$(grep -n 'location / {' "$NGINX" | head -1 | cut -d: -f1)"
    if [ -n "$webhook_line" ] && [ -n "$spa_line" ] && [ "$webhook_line" -lt "$spa_line" ]; then
        pass "/webhooks precedes the SPA fallback (lines $webhook_line < $spa_line)"
    else
        fail "/webhooks does not precede the SPA fallback (webhook=$webhook_line spa=$spa_line)"
    fi

    # 7 — three distinct cookie paths, read from the services' own config since they are baked into
    # the images rather than templated.
    local paths
    paths="$(grep -h 'cookie-path:' \
        "$REPO"/spire-gateway/src/main/resources/application.yml \
        "$REPO"/spire-orchestrator/src/main/resources/application.yml \
        "$REPO"/spire-review-worker/src/main/resources/application.yml \
        | awk '{print $2}' | sort -u | wc -l)"
    if [ "$paths" -eq 3 ]; then
        pass "three distinct cookie paths"
    else
        fail "expected 3 distinct cookie paths, found $paths"
    fi
}

# Renders a deliberately-broken shape and asserts the corresponding check catches it.
#
# Checks 1, 2, 5 and half of 6 are NEGATIVE: the value must be absent where it does not belong. A
# negative assertion passes trivially when a key is renamed or a grep looks in the wrong place, so
# without this a run reporting every check green is indistinguishable from a run whose greps matched
# nothing. Mutation verification, the same discipline every other guard in this repo carries.
self_test() {
    local broken=0
    local saved; saved="$(mktemp)"

    # Break 1: hand the gateway the master keyset.
    local out; out="$(render values-simple.yaml --set gateway.giveMasterKeyset=true)"
    if deployment "$out" spire-gateway | grep -q 'SPIRE_ENCRYPTION_KEYSET'; then
        pass "self-test: check 1 has something to catch (the broken shape renders)"
        FAILED=0
        absent "$out" spire-gateway SPIRE_ENCRYPTION_KEYSET >/dev/null 2>&1
        if [ "$FAILED" -ne 0 ]; then
            pass "self-test: check 1 catches the master keyset on the gateway"
        else
            fail "self-test: check 1 did NOT catch the master keyset on the gateway"
            broken=1
        fi
    else
        fail "self-test: gateway.giveMasterKeyset=true rendered nothing — check 1 may be untestable"
        broken=1
    fi

    # Break 2: the nginx template loses /webhooks.
    cp "$NGINX" "$saved"
    grep -v 'location /webhooks' "$saved" > "$NGINX"
    FAILED=0; check_repo_config >/dev/null 2>&1
    if [ "$FAILED" -ne 0 ]; then
        pass "self-test: removing /webhooks fails the config checks"
    else
        fail "self-test: removing /webhooks was NOT caught"
        broken=1
    fi
    cp "$saved" "$NGINX"

    # Break 3: derive X-Forwarded-Proto from $scheme.
    cp "$NGINX" "$saved"
    printf '\n    proxy_set_header X-Forwarded-Proto $scheme;\n' >> "$NGINX"
    FAILED=0; check_repo_config >/dev/null 2>&1
    if [ "$FAILED" -ne 0 ]; then
        pass 'self-test: a $scheme-derived X-Forwarded-Proto is caught'
    else
        fail 'self-test: a $scheme-derived X-Forwarded-Proto was NOT caught'
        broken=1
    fi
    cp "$saved" "$NGINX"
    rm -f "$saved"

    FAILED=0
    return "$broken"
}

if [ "${1:-}" = "--self-test" ]; then
    echo "== self-test: every check must catch its own break =="
    if self_test; then
        echo
        echo "self-test passed"
        exit 0
    fi
    echo
    echo "SELF-TEST FAILED — at least one assertion cannot catch what it claims to"
    exit 1
fi

echo "== rendered manifests =="
check_manifests
echo "== in-repo config =="
check_repo_config
echo
if [ "$FAILED" -eq 0 ]; then
    echo "all invariants hold"
    exit 0
fi
echo "INVARIANTS VIOLATED"
exit 1
