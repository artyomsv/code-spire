#!/usr/bin/env bash
# Renders the chart to plain YAML so `kubectl apply -f` users receive the SAME manifests as Helm and
# kustomize users.
#
#   ./deploy/render-manifests.sh          regenerate deploy/k8s/**
#   ./deploy/render-manifests.sh --check  fail if the committed output is not what the chart renders
#
# --check is the point. Three consumers of one chart drift silently otherwise, and "remember to
# regenerate" is not a mechanism — this turns it into a build failure.
#
# Values come from each kustomize overlay's values-required.yaml, so the rendered manifests and the
# kustomize output are fed identical inputs. Placeholders are obviously non-real (example.invalid):
# committed manifests must never look like a live deployment.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

status=0
for preset in simple production; do
    out="$HERE/k8s/$preset/spire.yaml"
    mkdir -p "$(dirname "$out")"
    rendered="$(helm template spire "$HERE/helm/spire" \
        -f "$HERE/helm/spire/values-$preset.yaml" \
        -f "$HERE/kustomize/overlays/$preset/values-required.yaml")"

    if [ "$CHECK" -eq 0 ]; then
        printf '%s\n' "$rendered" > "$out"
        echo "  wrote $out"
        continue
    fi

    if [ ! -f "$out" ]; then
        echo "MISSING: $out has never been generated. Run ./deploy/render-manifests.sh and commit."
        status=1
        continue
    fi
    if printf '%s\n' "$rendered" | diff -q "$out" - >/dev/null 2>&1; then
        echo "  ok  $out matches the chart"
    else
        echo "DRIFT: $out is not what the chart renders. Run ./deploy/render-manifests.sh and commit."
        printf '%s\n' "$rendered" | diff -u "$out" - | head -40 || true
        status=1
    fi
done

exit "$status"
