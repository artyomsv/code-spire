#!/usr/bin/env bash
#
# Builds the reference Codex agent image WITH its model catalogue baked in.
#
# Why this script exists at all: a Dockerfile cannot set a LABEL from a RUN's output. The value has to
# exist before the build that carries it, and the value can only come from the binary that build
# installs. So the image is built twice — once to get a binary to ask, once to carry the answer. The
# second pass reuses the whole cache except the label layer, so it costs a second, not a rebuild.
#
#   ./deploy/agent/build-codex.sh [tag]        # default tag: spire-agent-codex:latest
#
# `docker build -f deploy/agent/codex/Dockerfile -t spire-agent-codex:latest deploy/agent` still works
# and still produces a runnable agent. What it does NOT produce is the model label, and the factory then
# has no model list for that image — which the settings screen says rather than guessing.
set -euo pipefail

TAG="${1:-spire-agent-codex:latest}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKERFILE="$HERE/codex/Dockerfile"

echo "==> pass 1: building $TAG without a model catalogue"
docker build -f "$DOCKERFILE" -t "$TAG" "$HERE"

# `codex debug models` renders the raw catalogue as JSON and needs no sign-in (measured 2026-09-18 on
# @openai/codex@0.146.0). The raw document is ~314 KB and almost all of it describes things a screen has
# no use for, so it is trimmed here to the six fields the factory shows or enforces:
#
#   s  slug                    what --model is given
#   n  display name            what the operator reads
#   d  default reasoning level that model's OWN default, not a global one
#   e  supported levels        the levels THIS model allows, which differ per model
#   v  visibility              the vendor's own "show this one" flag
#   p  priority                the vendor's own ordering
#
# Models the vendor marks as not usable through the API are dropped: an API-key run cannot call them,
# and a subscription run is not a reason to offer a model that half this deployment cannot use.
#
# Trimmed inside the container, with the node that is already there, so this script needs nothing on the
# host but docker. Node is present because the image is node-based and the CLI ships through npm.
echo "==> reading the model catalogue from the image"
MODELS="$(docker run --rm --entrypoint sh "$TAG" -c '
  codex debug models 2>/dev/null | node -e "
    let raw = \"\";
    process.stdin.on(\"data\", chunk => raw += chunk);
    process.stdin.on(\"end\", () => {
      const parsed = JSON.parse(raw);
      const trimmed = (parsed.models || [])
        .filter(model => model.supported_in_api)
        .map(model => ({
          s: model.slug,
          n: model.display_name,
          d: model.default_reasoning_level,
          e: (model.supported_reasoning_levels || []).map(level => level.effort),
          v: model.visibility,
          p: model.priority,
        }));
      if (trimmed.length === 0) throw new Error(\"the catalogue named no API-usable model\");
      process.stdout.write(Buffer.from(JSON.stringify(trimmed)).toString(\"base64\"));
    });
  "
')"

if [ -z "$MODELS" ]; then
  # Loudly, at BUILD time. `codex debug models` lives under `debug`, so the vendor may move or remove
  # it — and the whole reason the catalogue is read during a build is that this failure lands on
  # whoever built the image, rather than on an operator opening a settings page months later.
  echo "FAILED: the image produced no model catalogue." >&2
  echo "  \`codex debug models\` answered nothing usable. If the vendor changed that command, this" >&2
  echo "  script is what has to change — not the screens that read the label." >&2
  exit 1
fi

echo "==> $(printf '%s' "$MODELS" | base64 -d | node -e 'let r="";process.stdin.on("data",c=>r+=c);process.stdin.on("end",()=>console.log(JSON.parse(r).map(m=>m.s).join(", ")))')"

echo "==> pass 2: baking the catalogue into $TAG"
docker build -f "$DOCKERFILE" --build-arg AGENT_MODELS="$MODELS" -t "$TAG" "$HERE"

echo "==> done. $TAG carries dev.codespire.agent.models ($(printf '%s' "$MODELS" | wc -c) bytes)"
