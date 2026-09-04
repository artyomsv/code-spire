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
#
# APK_UPGRADE_BUST for the reason the root Dockerfile records at length: with no changing input this
# layer caches on the base image alone, so the upgrade runs once and then refreshes only when the
# base retags — the wait it exists to skip. This image is not in docker.yml yet, so nothing passes a
# per-build value and the default applies; it is wired now so joining that matrix is a one-line
# change rather than a rediscovery of the same defect.
ARG APK_UPGRADE_BUST=local
RUN echo "apk upgrade for build ${APK_UPGRADE_BUST}" \
    && apk --no-cache upgrade \
    && adduser -D -u 1001 -h /home/spire spire \
    && mkdir -p /workspace /handoff \
    && chown 1001:1001 /workspace /handoff

COPY build/install/spire-publisher/lib /opt/spire-publisher/lib
COPY --chmod=755 docker/spire-clone.sh /usr/local/bin/spire-clone
COPY --chmod=755 docker/spire-publish.sh /usr/local/bin/spire-publish

USER 1001:1001
ENV HOME=/home/spire
