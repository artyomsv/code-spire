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
# RENDER_CHART renders a copy of the chart instead (the appVersion check edits one); values still come
# from the real chart.
render() {
    local values="$1"; shift
    helm template spire "${RENDER_CHART:-$CHART}" -f "$CHART/$values" \
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

# One rendered object of any kind, by its metadata name (indented two spaces, so a container or port
# called the same does not match).
object() {   # object <rendered> <kind> <name>
    printf '%s' "$1" | awk -v kind="$2" -v want="$3" '
        BEGIN { RS = "\n---\n" }
        $0 ~ ("\nkind: " kind "\n") && $0 ~ ("\n  name: " want "\n") { print }
    '
}

# The Secret and key an env entry of a Deployment reads, as "<secret>/<key>".
secret_ref() {   # secret_ref <rendered> <deployment> <env name>
    deployment "$1" "$2" | grep -A4 -- "- name: $3\$" \
        | awk '/secretKeyRef:/ { f = 1 } f && /^ *name:/ { n = $2 } f && /^ *key:/ { k = $2 } END { print n "/" k }'
}

expect_ref() {   # expect_ref <rendered> <deployment> <env name> <secret/key>
    local got; got="$(secret_ref "$1" "$2" "$3")"
    if [ "$got" = "$4" ]; then pass "$2 reads $3 from $4"; else fail "$2 reads $3 from '$got', expected $4"; fi
}

# The names of the Services whose type reaches outside the cluster.
external_services() {   # external_services <rendered>
    printf '%s' "$1" | awk '
        BEGIN { RS = "\n---\n" }
        {
            n = split($0, l, "\n"); svc = 0; ext = 0; name = ""
            for (i = 1; i <= n; i++) {
                if (l[i] == "kind: Service") svc = 1
                if (name == "" && l[i] ~ /^  name: /) name = substr(l[i], 9)
                if (l[i] ~ /^  type: (NodePort|LoadBalancer)$/) ext = 1
            }
            if (svc && ext) print name
        }
    '
}

# 5 — the dashboard is the only Service that may leave the cluster. trusted-proxies means nothing if a
# service port is reachable directly, because anything that reaches it can forge X-Forwarded-For.
# ui.service.type makes spire-ui external through values; nothing may make another Service external.
only_ui_external() {   # only_ui_external <rendered> <label>
    local others
    others="$(external_services "$1" | grep -vx 'spire-ui' | tr '\n' ' ')"
    if [ -z "$others" ]; then
        pass "no Service but spire-ui is externally typed ($2)"
    else
        fail "externally typed Services other than spire-ui ($2): $others"
    fi
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

    # 5 — see only_ui_external. Here on the default render; check_any_cluster repeats it with the
    # dashboard made external.
    only_ui_external "$out" "default render"

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

# The values that let the chart install on any cluster (docs/superpowers/specs/2026-11-15-k8s-any-
# cluster-design.md): ui.service, extraEnv/extraEnvFrom, the password references, the storage classes,
# image.tag, and the CloudNativePG example. The defaults must render today's shape, so each value is
# checked both unset and set. The refusals are in self_test, because each is proven by a render that
# must FAIL.
check_any_cluster() {
    local out svc

    # 9 — the defaults render today's dashboard Service and today's password references.
    out="$(render values-simple.yaml)"
    svc="$(object "$out" Service spire-ui)"
    if printf '%s\n' "$svc" | grep -qx '  type: ClusterIP'; then
        pass "spire-ui Service is ClusterIP by default"
    else
        fail "spire-ui Service is not ClusterIP by default: $(printf '%s\n' "$svc" | grep 'type:')"
    fi
    if printf '%s' "$out" | grep -q 'loadBalancerSourceRanges'; then
        fail "the default render has loadBalancerSourceRanges"
    else
        pass "the default render has no loadBalancerSourceRanges"
    fi
    if printf '%s' "$out" | grep -q 'storageClassName'; then
        fail "the default render names a storage class; empty must mean the cluster default"
    else
        pass "the default render names no storage class"
    fi
    expect_ref "$out" spire-orchestrator QUARKUS_DATASOURCE_PASSWORD TEST-spire-secrets/POSTGRES_PASSWORD
    expect_ref "$out" spire-worker       QUARKUS_DATASOURCE_PASSWORD TEST-spire-secrets/POSTGRES_PASSWORD
    expect_ref "$out" spire-gateway      QUARKUS_DATASOURCE_PASSWORD TEST-spire-gateway-secrets/GATEWAY_POSTGRES_PASSWORD

    # 10 — an empty image.tag is the chart's appVersion. Rendered from a copy whose appVersion is a
    # TEST- value, because the repository's appVersion is `edge`: a template that hard-coded `edge`
    # would pass a check against the real chart.
    local copy n
    copy="$(mktemp -d)"
    cp -R "$CHART" "$copy/spire"
    sed -i.bak 's/^appVersion:.*/appVersion: "TEST-appversion"/' "$copy/spire/Chart.yaml"
    out="$(RENDER_CHART="$copy/spire" render values-simple.yaml)"
    n="$(printf '%s' "$out" | grep -c 'image: "ghcr.io/artyomsv/spire-[a-z-]*:TEST-appversion"')"
    if [ "$n" -eq 4 ]; then
        pass "an empty image.tag pulls the chart's appVersion (4 images)"
    else
        fail "an empty image.tag should pull the appVersion on all 4 images, found $n"
    fi
    out="$(RENDER_CHART="$copy/spire" render values-simple.yaml --set image.tag=TEST-tag)"
    n="$(printf '%s' "$out" | grep -c 'image: "ghcr.io/artyomsv/spire-[a-z-]*:TEST-tag"')"
    if [ "$n" -eq 4 ]; then
        pass "a set image.tag wins over the appVersion (4 images)"
    else
        fail "a set image.tag should name all 4 images, found $n"
    fi
    rm -rf "$copy"

    # 11 — ui.service: LoadBalancer with source ranges renders both; ranges on a ClusterIP render none.
    out="$(render values-simple.yaml --set ui.service.type=LoadBalancer \
        --set 'ui.service.loadBalancerSourceRanges={192.0.2.0/24}' \
        --set ui.service.annotations.TEST-annotation=TEST)"
    svc="$(object "$out" Service spire-ui)"
    if printf '%s\n' "$svc" | grep -qx '  type: LoadBalancer' \
        && printf '%s\n' "$svc" | grep -A1 -x '  loadBalancerSourceRanges:' | grep -qx '    - 192.0.2.0/24' \
        && printf '%s\n' "$svc" | grep -qx '    TEST-annotation: TEST'; then
        pass "ui.service LoadBalancer renders its type, source ranges and annotations"
    else
        fail "ui.service LoadBalancer did not render type, ranges and annotations: $svc"
    fi
    if external_services "$out" | grep -qx 'spire-ui'; then
        pass "spire-ui is seen as external when its type is LoadBalancer"
    else
        fail "external_services does not see a LoadBalancer spire-ui, so check 5 is blind"
    fi
    only_ui_external "$out" "spire-ui as LoadBalancer"
    out="$(render values-simple.yaml --set 'ui.service.loadBalancerSourceRanges={192.0.2.0/24}')"
    if printf '%s' "$out" | grep -q 'loadBalancerSourceRanges'; then
        fail "loadBalancerSourceRanges rendered on a ClusterIP Service"
    else
        pass "loadBalancerSourceRanges renders only for a LoadBalancer"
    fi

    # 12 — extraEnv and extraEnvFrom reach their own service and no other.
    local service d
    for service in orchestrator gateway worker ui; do
        out="$(render values-simple.yaml \
            --set "$service.extraEnv[0].name=TEST_EXTRA_ENV" --set "$service.extraEnv[0].value=TEST" \
            --set "$service.extraEnvFrom[0].configMapRef.name=TEST-extra-config")"
        for d in spire-orchestrator spire-gateway spire-worker spire-ui; do
            if [ "$d" = "spire-$service" ]; then
                present "$out" "$d" 'name: TEST_EXTRA_ENV'
                present "$out" "$d" 'name: TEST-extra-config'
            else
                absent "$out" "$d" 'TEST_EXTRA_ENV'
                absent "$out" "$d" 'TEST-extra-config'
            fi
        done
    done

    # 13 — the refusals are narrow: the same shapes aimed at another Secret, or on a service that
    # holds the master keyset anyway, render. A refusal that fired on every secretKeyRef would pass
    # self_test and break every real use.
    local args
    for args in \
        'gateway.extraEnv[0].name=TEST_EXTRA_ENV gateway.extraEnv[0].valueFrom.secretKeyRef.name=TEST-other-secret gateway.extraEnv[0].valueFrom.secretKeyRef.key=TEST' \
        'gateway.extraEnvFrom[0].secretRef.name=TEST-other-secret' \
        'orchestrator.extraEnvFrom[0].secretRef.name=TEST-spire-secrets' \
        'postgres.gatewayPasswordSecret.name=TEST-other-secret postgres.gatewayPasswordSecret.key=password'; do
        local sets=() a
        for a in $args; do sets+=(--set "$a"); done
        out="$(render values-simple.yaml "${sets[@]}")"
        if [ $? -eq 0 ]; then
            pass "renders: $args"
        else
            fail "refused a shape that must render ($args): $(printf '%s' "$out" | grep Error)"
        fi
    done

    # 14 — the password references read any Secret and key, and only where they belong.
    out="$(render values-simple.yaml \
        --set postgres.passwordSecret.name=TEST-db-app --set postgres.passwordSecret.key=password \
        --set postgres.gatewayPasswordSecret.name=TEST-db-gateway --set postgres.gatewayPasswordSecret.key=password)"
    expect_ref "$out" spire-orchestrator QUARKUS_DATASOURCE_PASSWORD TEST-db-app/password
    expect_ref "$out" spire-worker       QUARKUS_DATASOURCE_PASSWORD TEST-db-app/password
    expect_ref "$out" spire-gateway      QUARKUS_DATASOURCE_PASSWORD TEST-db-gateway/password

    # 15 — the storage classes reach their PVC.
    out="$(render values-simple.yaml --set kafka.storageClass=TEST-kafka-sc --set postgres.storageClass=TEST-pg-sc)"
    if object "$out" StatefulSet spire-redpanda | grep -qx '        storageClassName: "TEST-kafka-sc"' \
        && object "$out" StatefulSet spire-postgres | grep -qx '        storageClassName: "TEST-pg-sc"'; then
        pass "kafka.storageClass and postgres.storageClass reach their PVCs"
    else
        fail "a storage class did not reach its PVC"
    fi

    # 16 — the CloudNativePG example, rendered with NO --set so a value it forgets fails here.
    local cnpg rc
    cnpg="$(helm template spire "$CHART" -f "$CHART/values-production.yaml" \
        -f "$REPO/deploy/examples/cloudnative-pg/values.yaml" 2>&1)"
    rc=$?
    if [ "$rc" -ne 0 ]; then
        fail "the CloudNativePG example does not render on its own: $(printf '%s' "$cnpg" | grep Error)"
        return
    fi
    pass "the CloudNativePG example renders with no --set"
    if printf '%s' "$cnpg" | grep -q 'name: spire-gateway-role'; then
        fail "the CloudNativePG example renders the gateway-role Job; its owner cannot CREATE ROLE"
    else
        pass "the CloudNativePG example has no gateway-role Job"
    fi
    if printf '%s\n' "$cnpg" | grep -qx 'kind: Ingress'; then
        fail "the CloudNativePG example renders an Ingress"
    else
        pass "the CloudNativePG example has no Ingress"
    fi
    if [ -n "$(object "$cnpg" StatefulSet spire-redpanda)" ]; then
        pass "the CloudNativePG example bundles Redpanda"
    else
        fail "the CloudNativePG example has no Redpanda StatefulSet"
    fi
    if [ -z "$(object "$cnpg" StatefulSet spire-postgres)" ] && ! printf '%s' "$cnpg" | grep -q 'spire-keycloak'; then
        pass "the CloudNativePG example bundles neither Postgres nor Keycloak"
    else
        fail "the CloudNativePG example bundles Postgres or Keycloak"
    fi
    expect_ref "$cnpg" spire-orchestrator QUARKUS_DATASOURCE_PASSWORD spire-db-app/password
    expect_ref "$cnpg" spire-worker       QUARKUS_DATASOURCE_PASSWORD spire-db-app/password
    expect_ref "$cnpg" spire-gateway      QUARKUS_DATASOURCE_PASSWORD spire-db-gateway/password
}

check_repo_config() {
    local conf; conf="$(cat "$NGINX")"

    # 0 — the e2e suite's SSRF-guard relaxation must never reach a packaged run.
    #
    # deploy/compose.e2e.yml sets SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS=true so a
    # Docker-network GitLab can be registered. That is correct THERE and nowhere else: an operator
    # who inherited it would be running with the guard on operator-supplied provider base URLs
    # switched off, and nothing would say so. It is a literal rather than ${VAR} in the overlay
    # precisely so no .env value can turn it on — this asserts the other half, that the string does
    # not appear in the files a packaged deployment is built from.
    local packaged
    for packaged in "$REPO/deploy/compose.yml" "$REPO/deploy/compose.ghcr.yml" \
                    "$REPO/deploy/.env.example"; do
        if grep -q 'SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS' "$packaged" 2>/dev/null; then
            fail "$(basename "$packaged") mentions SPIRE_SECURITY_ALLOW_INSECURE_PROVIDER_URLS — that "\
"relaxation belongs to deploy/compose.e2e.yml alone"
        else
            pass "$(basename "$packaged") does not relax the provider-URL guard"
        fi
    done

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

    # Break 6: a Service other than the dashboard becomes external. No value can do that today, so the
    # forbidden shape is made by editing a render: check 5 must catch it.
    out="$(render values-simple.yaml --set ui.service.type=LoadBalancer)"
    out="$(printf '%s' "$out" | awk 'BEGIN { RS = "\n---\n"; ORS = "\n---\n" }
        /\nkind: Service\n/ && /\n  name: spire-orchestrator\n/ { sub(/\n  type: ClusterIP\n/, "\n  type: NodePort\n") }
        { print }')"
    if external_services "$out" | grep -qx 'spire-orchestrator'; then
        FAILED=0; only_ui_external "$out" "self-test" >/dev/null 2>&1
        if [ "$FAILED" -ne 0 ]; then
            pass "self-test: check 5 catches an external Service other than spire-ui"
        else
            fail "self-test: check 5 did NOT catch an external spire-orchestrator Service"
            broken=1
        fi
    else
        fail "self-test: the edited render has no external spire-orchestrator — break 6 tests nothing"
        broken=1
    fi

    # Breaks 7+: the chart's render-time refusals (spec sections 2 and 3). render.sh greps env NAMES,
    # so it cannot see an envFrom or a secretKeyRef by itself; the refusals are the guard, and each one
    # is proven here by rendering its forbidden shape. The render must FAIL, and fail with the refusal's
    # own message: a render that failed for another reason would prove nothing.
    refuses "a repeated chart-owned name on the orchestrator" \
        'orchestrator.extraEnv[0] sets SPIRE_OIDC_CLIENT_ID, which this container already has' \
        --set orchestrator.extraEnv[0].name=SPIRE_OIDC_CLIENT_ID --set orchestrator.extraEnv[0].value=TEST || broken=1
    refuses "a repeated chart-owned name on the gateway (its database user)" \
        'gateway.extraEnv[0] sets QUARKUS_DATASOURCE_USERNAME, which this container already has' \
        --set gateway.extraEnv[0].name=QUARKUS_DATASOURCE_USERNAME --set gateway.extraEnv[0].value=spire || broken=1
    refuses "a repeated chart-owned name on the worker" \
        'worker.extraEnv[0] sets SPIRE_ENCRYPTION_KEYSET, which this container already has' \
        --set worker.extraEnv[0].name=SPIRE_ENCRYPTION_KEYSET --set worker.extraEnv[0].value=TEST || broken=1
    refuses "a repeated chart-owned name on the dashboard" \
        'ui.extraEnv[0] sets SPIRE_GATEWAY_URL, which this container already has' \
        --set ui.extraEnv[0].name=SPIRE_GATEWAY_URL --set ui.extraEnv[0].value=http://TEST.example.invalid || broken=1
    refuses "the same name twice in one extraEnv" \
        'orchestrator.extraEnv[1] sets TEST_EXTRA_ENV, which this container already has' \
        --set orchestrator.extraEnv[0].name=TEST_EXTRA_ENV --set orchestrator.extraEnv[0].value=TEST \
        --set orchestrator.extraEnv[1].name=TEST_EXTRA_ENV --set orchestrator.extraEnv[1].value=TEST || broken=1
    refuses "SPIRE_ENCRYPTION_KEYSET on the gateway as a plain value" \
        'gateway.extraEnv[0] sets SPIRE_ENCRYPTION_KEYSET' \
        --set gateway.extraEnv[0].name=SPIRE_ENCRYPTION_KEYSET --set gateway.extraEnv[0].value=TEST || broken=1
    refuses "SPIRE_ENCRYPTION_KEYSET on the gateway from another Secret" \
        'gateway.extraEnv[0] sets SPIRE_ENCRYPTION_KEYSET' \
        --set gateway.extraEnv[0].name=SPIRE_ENCRYPTION_KEYSET \
        --set gateway.extraEnv[0].valueFrom.secretKeyRef.name=TEST-other-secret \
        --set gateway.extraEnv[0].valueFrom.secretKeyRef.key=SPIRE_ENCRYPTION_KEYSET || broken=1
    refuses "a gateway extraEnv reading the main Secret" \
        'gateway.extraEnv[0] reads from "TEST-spire-secrets", the main Secret' \
        --set gateway.extraEnv[0].name=TEST_EXTRA_ENV \
        --set gateway.extraEnv[0].valueFrom.secretKeyRef.name=TEST-spire-secrets \
        --set gateway.extraEnv[0].valueFrom.secretKeyRef.key=POSTGRES_PASSWORD || broken=1
    refuses "a gateway extraEnvFrom loading the main Secret" \
        'gateway.extraEnvFrom[0] loads "TEST-spire-secrets", the main Secret' \
        --set gateway.extraEnvFrom[0].secretRef.name=TEST-spire-secrets || broken=1
    refuses "the gateway's database password read from the main Secret" \
        'postgres.gatewayPasswordSecret.name is "TEST-spire-secrets", the main Secret' \
        --set postgres.gatewayPasswordSecret.name=TEST-spire-secrets \
        --set postgres.gatewayPasswordSecret.key=password || broken=1
    refuses "a password Secret with a name and no key" \
        'postgres.passwordSecret needs both name and key, or neither' \
        --set postgres.passwordSecret.name=TEST-db-app || broken=1
    refuses "a password Secret with a key and no name" \
        'postgres.gatewayPasswordSecret needs both name and key, or neither' \
        --set postgres.gatewayPasswordSecret.key=password || broken=1
    refuses "a dashboard Service type the chart does not offer" \
        'ui.service.type is "ExternalName"' \
        --set ui.service.type=ExternalName || broken=1

    FAILED=0
    return "$broken"
}

# A forbidden shape must make the render fail with the refusal's own message.
refuses() {   # refuses <description> <message fragment> <helm args...>
    local what="$1" needle="$2" out rc
    shift 2
    out="$(render values-simple.yaml "$@")"
    rc=$?
    if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qF -- "$needle"; then
        pass "self-test: the chart refuses $what"
        return 0
    fi
    fail "self-test: the chart did NOT refuse $what (exit $rc): $(printf '%s' "$out" | grep Error | head -1)"
    return 1
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
echo "== any-cluster values =="
check_any_cluster
echo "== in-repo config =="
check_repo_config
echo
if [ "$FAILED" -eq 0 ]; then
    echo "all invariants hold"
    exit 0
fi
echo "INVARIANTS VIOLATED"
exit 1
