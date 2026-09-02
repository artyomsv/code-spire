# The publisher image: one JRE, the publisher distribution, and the two entrypoints a run unit
# names (RunUnitBuilder): `spire-clone` for the init container, `spire-publish` for the sidecar.
#
#   ./gradlew :spire-publisher:installDist && docker build -t spire-publisher:latest spire-publisher
#
# The context is this module directory; .dockerignore admits only the installed distribution and
# the launcher scripts, so no source, test or credential file can reach a layer. This image holds
# no credential of its own: both tokens are injected per run as environment.
FROM eclipse-temurin:25-jre-alpine

# Same two rules as the service images: close the base image's CVE backlog at build time, and run
# as a fixed non-root uid. The uid is the AGENT's (1001) on purpose — the init container populates
# /workspace and the agent must own what it wrote — and the mount points exist owned by it so a
# fresh named volume inherits that ownership rather than root's.
RUN apk --no-cache upgrade \
    && adduser -D -u 1001 -h /home/spire spire \
    && mkdir -p /workspace /handoff \
    && chown 1001:1001 /workspace /handoff

COPY build/install/spire-publisher/lib /opt/spire-publisher/lib
COPY --chmod=755 docker/spire-clone.sh /usr/local/bin/spire-clone
COPY --chmod=755 docker/spire-publish.sh /usr/local/bin/spire-publish

USER 1001:1001
ENV HOME=/home/spire
