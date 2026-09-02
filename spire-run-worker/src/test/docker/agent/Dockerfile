# The M0 test's agent image: git and the SAME entrypoint the reference Codex image ships
# (deploy/agent/spire-agent-entrypoint.sh, copied into this context by TestImages), so the test
# exercises the real handoff contract with a shell script standing in for the model.
FROM alpine:3.20
RUN apk add --no-cache git \
    && adduser -D -u 1001 agent \
    && mkdir -p /workspace /handoff \
    && chown 1001:1001 /workspace /handoff
COPY --chmod=755 spire-agent-entrypoint.sh /usr/local/bin/spire-agent-entrypoint
USER 1001:1001
ENV HOME=/home/agent SPIRE_WORKSPACE=/workspace SPIRE_HANDOFF=/handoff
WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/spire-agent-entrypoint"]
