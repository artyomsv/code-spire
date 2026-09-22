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

# The two DECLARED clauses of the image contract (docs/factory/AGENT-IMAGE-CONTRACT.md).
# Declarations, not proofs: `spire-agent-image verify` reports them under a heading that says it
# did not check them. Present here because the reference image should demonstrate the half of the
# report FR-F13 is mostly about — without them it prints "(no label)" twice, and the contract
# claimed otherwise.
LABEL dev.codespire.agent.toolchain=node
LABEL dev.codespire.agent.harness=codex

# The models THIS image's harness can run, and the thinking levels each one allows, as base64 of a
# trimmed `codex debug models`. The build script deploy/agent/build-codex.sh produces it; building
# this Dockerfile by hand leaves it empty, and the factory then has no model list for the image.
#
# WHY A LABEL. The alternative is running the image to ask it, which on Kubernetes means scheduling a
# pod to fill in a dropdown -- a capability that does not exist yet and would be written twice. Image
# metadata is the one thing every runtime must already be able to read, since it cannot pull otherwise.
#
# WHY IT CANNOT DRIFT. The value is generated FROM the binary in this image, during this build, and
# sealed into the same artifact. A new CLI version yields a new image and a new label; they ship or
# fail together.
#
# WHY BASE64. The trimmed value is JSON, and a raw JSON string would have to survive a Dockerfile, a
# shell, `docker inspect` output and a Kubernetes manifest without a quote being eaten anywhere.
# Base64 is [A-Za-z0-9+/=] and survives all four. `spire-agent-image verify` prints it decoded.
#
# An ARG, because a LABEL cannot read a RUN's output: the value must exist before the build that
# carries it. Declared here, after the heavy layers, so the second pass reuses the whole cache.
ARG AGENT_MODELS=""
LABEL dev.codespire.agent.models=$AGENT_MODELS

USER 1001:1001
ENV HOME=/home/agent \
    SPIRE_WORKSPACE=/workspace \
    SPIRE_HANDOFF=/handoff
WORKDIR /workspace

ENTRYPOINT ["/usr/local/bin/spire-agent-entrypoint"]
