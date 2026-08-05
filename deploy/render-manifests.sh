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

# The single source of truth for which Helm renders these manifests. manifests.yml reads this exact
# line to pin its setup-helm version, so CI and a workstation cannot disagree.
#
# It has to be pinned at all because Helm's output is not byte-identical across major versions — Helm 4
# emits document separators with different surrounding blank lines than Helm 3, which shows up as
# whitespace-only "drift" in a check whose whole job is to be trustworthy. That was not theoretical:
# these manifests were first generated with Helm 4 and failed CI under Helm 3.
REQUIRED_HELM=3.21.3

HERE="$(cd "$(dirname "$0")" && pwd)"
CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

actual_helm="$(helm version --template '{{.Version}}' 2>/dev/null | sed 's/^v//')"
if [ "$actual_helm" != "$REQUIRED_HELM" ]; then
    echo "This script needs Helm $REQUIRED_HELM; found ${actual_helm:-none}." >&2
    echo "Helm versions do not render byte-identical output, so a different one produces spurious" >&2
    echo "drift. Either install it, or run through the pinned image:" >&2
    echo >&2
    echo "  docker run --rm -v \"\$PWD:/w\" -w /w alpine/helm:$REQUIRED_HELM \\" >&2
    echo "    template spire deploy/helm/spire -f deploy/helm/spire/values-simple.yaml \\" >&2
    echo "    -f deploy/kustomize/overlays/simple/values-required.yaml > deploy/k8s/simple/spire.yaml" >&2
    exit 2
fi

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
