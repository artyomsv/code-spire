#!/usr/bin/env bash
#
# Builds the reference Claude Code agent image WITH its model list baked in.
#
#   ./deploy/agent/build-claude-code.sh [tag]   # default tag: spire-agent-claude-code:latest
#
# One pass, unlike build-codex.sh: Claude Code has no command that prints its model list without a
# credential, so the list is deploy/agent/claude-code/models.json (the ModelCatalogueLabel short form,
# written from Anthropic's model list) and exists before the build that carries it.
set -euo pipefail

TAG="${1:-spire-agent-claude-code:latest}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKERFILE="$HERE/claude-code/Dockerfile"
MODELS_FILE="$HERE/claude-code/models.json"

# Encoded with node, as build-codex.sh decodes its list: `base64 -w0` is GNU only and fails on macOS and BSD.
AGENT_MODELS="$(node -e 'process.stdout.write(require("fs").readFileSync(process.argv[1]).toString("base64"))' "$MODELS_FILE")"
if [ -z "$AGENT_MODELS" ]; then
  echo "FAILED: $MODELS_FILE is empty; the image would carry no model list." >&2
  exit 1
fi

echo "==> building $TAG with the model list from $MODELS_FILE"
docker build -f "$DOCKERFILE" --build-arg AGENT_MODELS="$AGENT_MODELS" -t "$TAG" "$HERE"

echo "==> done. $TAG carries dev.codespire.agent.models (${#AGENT_MODELS} bytes)"
