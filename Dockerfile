# syntax=docker/dockerfile:1
#
# Production image for the three Quarkus services. ONE Dockerfile parameterised by SERVICE, which
# matches the in-repo precedent: Dockerfile.dev is already one image parameterised by compose.
#
#   docker build --build-arg SERVICE=gateway       -t spire-gateway .
#   docker build --build-arg SERVICE=orchestrator  -t spire-orchestrator .
#   docker build --build-arg SERVICE=review-worker -t spire-review-worker .
#
ARG SERVICE

FROM eclipse-temurin:25-jdk AS build
ARG SERVICE
WORKDIR /workspace

# Build files first, so the dependency layer caches independently of source edits.
COPY gradlew settings.gradle.kts gradle.properties build.gradle.kts ./
COPY gradle/ gradle/
COPY spire-arch/build.gradle.kts spire-arch/
COPY spire-context-code/build.gradle.kts spire-context-code/
COPY spire-context-confluence/build.gradle.kts spire-context-confluence/
COPY spire-context-github/build.gradle.kts spire-context-github/
COPY spire-context-gitlab/build.gradle.kts spire-context-gitlab/
COPY spire-context-jira/build.gradle.kts spire-context-jira/
COPY spire-contract/build.gradle.kts spire-contract/
COPY spire-diff/build.gradle.kts spire-diff/
# Not a dependency of any service — but settings.gradle.kts includes it, and Gradle refuses to
# configure an included project whose directory is absent. Every module in settings must appear in
# this list or `:spire-<service>:dependencies` fails before a single service class is compiled.
COPY spire-e2e/build.gradle.kts spire-e2e/
COPY spire-encryption/build.gradle.kts spire-encryption/
COPY spire-gateway/build.gradle.kts spire-gateway/
COPY spire-harness/build.gradle.kts spire-harness/
COPY spire-harness-codex/build.gradle.kts spire-harness-codex/
COPY spire-http/build.gradle.kts spire-http/
COPY spire-llm/build.gradle.kts spire-llm/
COPY spire-orchestrator/build.gradle.kts spire-orchestrator/
COPY spire-review-worker/build.gradle.kts spire-review-worker/
COPY spire-publisher/build.gradle.kts spire-publisher/
COPY spire-run-worker/build.gradle.kts spire-run-worker/
COPY spire-runtime/build.gradle.kts spire-runtime/
COPY spire-runtime-docker/build.gradle.kts spire-runtime-docker/
COPY spire-scm-bitbucket/build.gradle.kts spire-scm-bitbucket/
COPY spire-scm-github/build.gradle.kts spire-scm-github/
COPY spire-scm-gitlab/build.gradle.kts spire-scm-gitlab/
COPY spire-workspace/build.gradle.kts spire-workspace/

# A Windows checkout gives gradlew CRLF and /bin/sh then rejects the shebang with
# "bad interpreter: /bin/sh^M". CI on Linux never sees this; a local deploy/compose.yml build does.
# Same normalisation Dockerfile.dev applies, for the same reason.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# Resolve dependencies with only the build files present, so this layer survives every source edit.
RUN ./gradlew --no-daemon --console=plain :spire-${SERVICE}:dependencies \
      --configuration runtimeClasspath > /dev/null

COPY . .

# Flyway checksums are a CRC over file bytes, so a CRLF migration hashes differently from the LF the
# database was migrated with and boot fails on a checksum mismatch.
RUN sed -i 's/\r$//' gradlew \
 && find . -path '*/db/migration/*.sql' -exec sed -i 's/\r$//' {} +

# Tests already ran in ci.yml. Re-running here would run them once per architecture under QEMU.
RUN ./gradlew --no-daemon --console=plain :spire-${SERVICE}:build -x test


FROM eclipse-temurin:25-jre-alpine
ARG SERVICE

LABEL org.opencontainers.image.title="spire-${SERVICE}" \
      org.opencontainers.image.description="Code Spire ${SERVICE} — source-available, self-hosted AI code reviewer" \
      org.opencontainers.image.source="https://github.com/artyomsv/code-spire" \
      org.opencontainers.image.licenses="FSL-1.1-ALv2"

# Each service's application.yml sets quarkus.http.port from its own ${*_HTTP_PORT:3408x} default.
# This targets quarkus.http.port directly and beats that default, so all three images listen where
# EXPOSE and HEALTHCHECK say they do. Without it the container reports unhealthy forever and
# compose's `depends_on: service_healthy` never releases. One variable covers all three services.
ENV QUARKUS_HTTP_PORT=8080 \
    QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_PROFILE=prod

# eclipse-temurin retags on its own cadence, which is slower than Alpine's package index moves. The
# gap is where every OS-level CVE Trivy reports on these three images comes from — libexpat and
# p11-kit, neither of which this image installs or uses directly, both inherited from the base. An
# upgrade here closes them at build time instead of waiting for an upstream retag that may never come
# for a given tag.
#
# It does cost reproducibility: two builds of the same commit a week apart can now carry different
# package versions. That is the accepted trade — the alternative is pinning each package to a version
# that itself goes stale, which is the same treadmill with an extra step. The image digest is what
# deployments pin (deploy/ resolves to sha-<short>), so a given deployed artifact is still exact.
# spire-ui's base needs none of this: it is scanned by the same job and reports clean.
RUN apk --no-cache upgrade

RUN addgroup -g 1001 spire && adduser -u 1001 -G spire -s /bin/sh -D spire
WORKDIR /app

# The fast-jar in four layers, lib/ first. lib/ is hundreds of MB of unchanging dependencies while
# app/ is about a megabyte of our own classes; copied as one directory, every code change re-pushes
# the whole thing, which on a JVM image is the difference between a seconds-long and a minutes-long
# push.
COPY --from=build --chown=1001:1001 /workspace/spire-${SERVICE}/build/quarkus-app/lib/ ./lib/
COPY --from=build --chown=1001:1001 /workspace/spire-${SERVICE}/build/quarkus-app/*.jar ./
COPY --from=build --chown=1001:1001 /workspace/spire-${SERVICE}/build/quarkus-app/app/ ./app/
COPY --from=build --chown=1001:1001 /workspace/spire-${SERVICE}/build/quarkus-app/quarkus/ ./quarkus/

# The SERVICE's own licence, not the repo root's — the root LICENSE is a pointer to LICENSING.md,
# while each deployable carries the FSL-1.1-ALv2 text the OCI label above declares (ADR-021).
COPY --from=build --chown=1001:1001 /workspace/spire-${SERVICE}/LICENSE ./LICENSE
COPY --from=build --chown=1001:1001 /workspace/NOTICE /workspace/LICENSING.md ./

USER 1001
EXPOSE 8080

# start-period covers JVM boot plus Flyway migration on a cold database.
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
  CMD wget -qO- http://localhost:8080/q/health/ready || exit 1

ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
