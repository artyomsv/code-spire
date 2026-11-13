# The reference agent image for the Claude Code arm (harness reviews 3b, ADR-050).
#
# A copy of deploy/agent/codex/Dockerfile with three changes: the npm package, the harness label,
# and CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC. Build it with the script, which bakes in the model
# list:
#
#   ./deploy/agent/build-claude-code.sh
#
# This image holds NO credential. Claude Code runs only behind the per-run model proxy: the agent is
# given a placeholder key and the proxy's address, and the real Anthropic API key never enters this
# container. It pays with an API key only; a Claude subscription is never a path (docs/factory/
# AGENT-IMAGE-CONTRACT.md).
FROM node:22-bookworm-slim

# ca-certificates is NOT optional: without it every TLS call fails "invalid peer certificate:
# UnknownIssuer". Behind the model proxy the agent speaks plain HTTP to spire-model:8080, but git and
# an unproxied run still need the trust store.
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
        ca-certificates git openssh-client \
    && rm -rf /var/lib/apt/lists/*

# Pinned. Every flag the adapter uses is read from this version's --help, and what it loads from a
# workspace is measured on this version (harness reviews 3b, Task 3).
RUN npm i -g @anthropic-ai/claude-code@2.1.319 && npm cache clean --force

# The mount points exist, owned by the agent user, so a fresh named volume mounted there inherits
# that ownership; without this the volume is root's and the agent cannot write its own workspace.
RUN useradd -m -u 1001 -s /bin/bash agent \
    && mkdir -p /workspace /handoff \
    && chown 1001:1001 /workspace /handoff

COPY --chmod=755 spire-agent-entrypoint.sh /usr/local/bin/spire-agent-entrypoint

# The two DECLARED clauses of the image contract (docs/factory/AGENT-IMAGE-CONTRACT.md).
LABEL dev.codespire.agent.toolchain=node
LABEL dev.codespire.agent.harness=claude_code

# The models THIS image's harness can run, as base64 of deploy/agent/claude-code/models.json. Unlike
# Codex, Claude Code has no command that prints its model list without a credential, so the list is a
# file in this repository, written from Anthropic's model list and checked against it when the CLI
# version changes. deploy/agent/build-claude-code.sh passes it; a plain docker build leaves it empty.
ARG AGENT_MODELS=""
LABEL dev.codespire.agent.models=$AGENT_MODELS

USER 1001:1001
# CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC turns off the CLI's own update checks, telemetry and error
# reporting, so the only traffic a run makes is its model calls, which the model proxy sees.
ENV HOME=/home/agent \
    SPIRE_WORKSPACE=/workspace \
    SPIRE_HANDOFF=/handoff \
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/spire-agent-entrypoint"]
