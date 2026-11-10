# The model proxy image: one JRE and the proxy distribution. Inside a review run unit it is the only
# container that holds the model credential; the agent reaches the vendor through it with a placeholder.
#
#   ./gradlew :spire-model-proxy:installDist && docker build -t spire-model-proxy:latest spire-model-proxy
#
# The context is this module directory; .dockerignore admits only the installed distribution, so no
# source, test or credential file can reach a layer. This image holds no credential of its own: the
# route and the credential are injected per run as environment (SPIRE_MODEL_*), and so are the
# corporate trust and the egress proxy.
FROM eclipse-temurin:25-jre-alpine

# Same two rules as the publisher image: close the base image's CVE backlog at build time, and run
# as a fixed non-root uid. 1002, not the agent's 1001: this container shares nothing on disk with
# the agent, and a different uid keeps it that way.
#
# APK_UPGRADE_BUST for the reason the root Dockerfile records: with no changing input this layer
# caches on the base image alone, so the upgrade would run once. Not in docker.yml yet; wired now so
# joining that matrix is a one-line change.
ARG APK_UPGRADE_BUST=local
RUN echo "apk upgrade for build ${APK_UPGRADE_BUST}" \
    && apk --no-cache upgrade \
    && adduser -D -u 1002 -h /home/spire spire

COPY build/install/spire-model-proxy/lib /opt/spire-model-proxy/lib

USER 1002:1002
EXPOSE 8080
ENTRYPOINT ["java","-XX:-UsePerfData","-XX:MaxRAMPercentage=75","-cp","/opt/spire-model-proxy/lib/*","dev.codespire.modelproxy.ModelProxy"]
