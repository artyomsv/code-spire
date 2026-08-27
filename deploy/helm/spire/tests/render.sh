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

    # 6 — the nginx template: every prefix routed, /webhooks before the SPA fallback, the six proxied
    # headers set on the server block with the right values and set NOWHERE else, an unpinned Host
    # forwarded with its port, Connection answered per request, and X-Forwarded-Proto NOT derived
    # from $scheme.
    #
    # Not covered here, deliberately: the proxy buffer sizes. They only manifest against a real
    # chunked OIDC session cookie, which needs a completed login round-trip through a live identity
    # provider — neither this script nor deploy/e2e.sh performs one. Tracked in techdebt/global/,
    # not silently omitted.
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

    # The six proxied headers, asserted BY VALUE and BY SCOPE — and the scope half is the point.
    #
    # nginx inherits proxy_set_header from an outer level only when the inner level defines none of
    # its own, so a single proxy_set_header inside a location silently discards all six at once:
    # Host falls back to $proxy_host, the upstream NAME, and redirect_uri points at a backend port
    # no realm can match. That is what shipped. The rule the template keeps is therefore "the server
    # block sets all six, no location sets any", and these are its two halves — everything before
    # the first location must carry each header with its exact value, and nothing from the first
    # location onwards may carry a proxy_set_header at all.
    #
    # By value, because names alone let the regression through: an earlier form asked only whether a
    # location mentioned Host, so dropping X-Forwarded-Proto passed — and a service then derives the
    # scheme from its own connection, minting an http:// redirect_uri behind a TLS Ingress and
    # breaking ONLY there. Neither half is a file-wide grep either: with the scope unanchored, the
    # right value anywhere in the file satisfied a check about the server block.
    local server_scope after_locations set_here header
    server_scope="$(printf '%s' "$conf" | sed -n '/^server {/,/^[[:space:]]*location[[:space:]]/p')"
    after_locations="$(printf '%s' "$conf" | sed -n '/^[[:space:]]*location[[:space:]]/,$p')"
    set_here="$(printf '%s' "$server_scope" | grep -E '^[[:space:]]*proxy_set_header' \
        | sed 's/;[[:space:]]*$//; s/^[[:space:]]*//; s/[[:space:]][[:space:]]*/ /g')"
    for header in \
        'Host $spire_host' \
        'X-Forwarded-Host $spire_host' \
        'X-Forwarded-Proto $spire_fwd_proto' \
        'X-Forwarded-For $proxy_add_x_forwarded_for' \
        'Upgrade $http_upgrade' \
        'Connection $spire_conn'; do
        if printf '%s\n' "$set_here" | grep -qxF "proxy_set_header $header"; then
            pass "the server block sets $header"
        else
            fail "the server block does not set '$header' — every location inherits that gap"
        fi
    done

    local leaked
    leaked="$(printf '%s' "$after_locations" | grep -E '^[[:space:]]*proxy_set_header' \
        | sed 's/^[[:space:]]*//' | tr '\n' ' ')"
    if [ -z "$leaked" ]; then
        pass 'no location sets a header of its own, so all six inherit everywhere'
    else
        fail "a location sets its own header, which discards all six from the server block: $leaked"
    fi

    # $host drops the port, so a dashboard on any port but 80 would produce a callback the realm
    # cannot match; $http_host carries it. That value now reaches Host through the pinning maps, so
    # this reads the fallback arm — the one every deployment that does not set SPIRE_PUBLIC_HOST
    # takes. Passing this with $host is how the packaged stack shipped a login that worked on port
    # 80 alone.
    local host_map conn_map
    host_map="$(printf '%s' "$conf" | sed -n '/^map \$spire_pinned_host \$spire_host {/,/^}/p')"
    if printf '%s' "$host_map" | grep -qF '$http_host;'; then
        pass 'an unpinned Host is forwarded with its port'
    else
        fail 'an unpinned Host is not forwarded with its port — the callback would drop it'
    fi

    # Connection is answered per request. A literal "upgrade" on the server block would be sent on
    # EVERY request, /webhooks and /wk included, telling them to switch protocols for nothing.
    conn_map="$(printf '%s' "$conf" | sed -n '/^map \$http_upgrade \$spire_conn {/,/^}/p')"
    if printf '%s' "$conn_map" | grep -q 'default upgrade;' && printf '%s' "$conn_map" | grep -q 'close;'; then
        pass 'Connection upgrades only when the request does'
    else
        fail 'Connection is not mapped from $http_upgrade — a literal would reach every request'
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

    # Break 4: a location acquires a header of its own — the exact shape that shipped, since a
    # WebSocket location is where Upgrade wants to live. It must fail the SCOPE half, because the
    # server block's other five headers stop reaching that location the moment this one line exists.
    cp "$NGINX" "$saved"
    awk '{ print; if ($0 ~ /proxy_pass \$spire_worker;/) print "        proxy_set_header Upgrade $http_upgrade;" }' \
        "$saved" > "$NGINX"
    FAILED=0; check_repo_config >/dev/null 2>&1
    if [ "$FAILED" -ne 0 ]; then
        pass 'self-test: a location setting a header of its own is caught'
    else
        fail 'self-test: a location setting a header of its own was NOT caught'
        broken=1
    fi
    cp "$saved" "$NGINX"

    # Break 5: the server block keeps Host and loses X-Forwarded-Proto. The VALUE half must fail —
    # the previous form of this check tracked Host alone, so this regression passed it, and it
    # breaks only behind a TLS-terminating Ingress where a plaintext compose run stays green.
    cp "$NGINX" "$saved"
    grep -v 'proxy_set_header X-Forwarded-Proto' "$saved" > "$NGINX"
    FAILED=0; check_repo_config >/dev/null 2>&1
    if [ "$FAILED" -ne 0 ]; then
        pass 'self-test: dropping one of the four forwarded headers is caught'
    else
        fail 'self-test: dropping X-Forwarded-Proto was NOT caught'
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
