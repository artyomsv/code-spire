# The reference agent image for the Codex arm (docs/factory/RUN-TOPOLOGY.md §11).
#
# An operator builds the repository's own toolchain FROM this image; the entrypoint is what makes
# it a factory agent rather than a shell with Codex in it. Build from the deploy/agent context so
# the shared entrypoint is in reach:
#
#   docker build -f deploy/agent/codex/Dockerfile -t spire-agent-codex:latest deploy/agent
#
# This image holds NO credential. The model key is injected per run (OPENAI_API_KEY) and the git
# token never enters this container at all — `docker image history` and `docker inspect` on a run's
# agent container must show neither, and the runbook checks both.
FROM node:22-bookworm-slim

# ca-certificates is NOT optional: without it every TLS call fails "invalid peer certificate:
# UnknownIssuer", and Codex retries silently rather than saying why.
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
        ca-certificates git openssh-client \
    && rm -rf /var/lib/apt/lists/*

# Pinned to the version the adapter's event parser was measured against (RUN-TOPOLOGY §10).
RUN npm i -g @openai/codex@0.146.0 && npm cache clean --force

# The mount points exist, owned by the agent user, so a fresh named volume mounted there inherits
# that ownership; without this the volume is root's and the agent cannot write its own workspace.
RUN useradd -m -u 1001 -s /bin/bash agent \
    && mkdir -p /workspace /handoff \
    && chown 1001:1001 /workspace /handoff

COPY --chmod=755 spire-agent-entrypoint.sh /usr/local/bin/spire-agent-entrypoint

USER 1001:1001
ENV HOME=/home/agent \
    SPIRE_WORKSPACE=/workspace \
    SPIRE_HANDOFF=/handoff
WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/spire-agent-entrypoint"]
