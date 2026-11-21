#!/usr/bin/env bash
#
# Builds the reference Claude Code agent image.
#
#   ./deploy/agent/build-claude-code.sh [tag]   # default tag: spire-agent-claude-code:latest
#
# The image carries no model list (no dev.codespire.agent.models label). Claude Code's CLI has no command
# that prints its models, and it runs any Anthropic API model by name, so the orchestrator offers it the
# enabled Anthropic models of the model catalog (ai-catalog/v1/catalog.yaml) and of Settings → LLM → Models.
# A new Claude model is therefore a catalog change, not an image rebuild.
set -euo pipefail

TAG="${1:-spire-agent-claude-code:latest}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKERFILE="$HERE/claude-code/Dockerfile"

echo "==> building $TAG"
docker build -f "$DOCKERFILE" -t "$TAG" "$HERE"

echo "==> done. $TAG (its model offer comes from the model catalog)"
