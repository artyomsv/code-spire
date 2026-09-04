# syntax=docker/dockerfile:1
#
# The dashboard image: static assets AND the reverse proxy that puts all four services on one origin.
# That proxy is what makes ADR-022's cookie-path scoping real in a packaged run — see
# nginx/default.conf.template for why each rule is the way it is.
# Build stage only — nothing from this line survives into the shipped image, but ci.yml's dashboard
# job pins the same major so the tsc/vitest/vite run that gates a PR uses the runtime that builds it.
FROM node:26-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build


# Unprivileged variant: runs as uid 101 and already listens on 8080, where the official image runs
# its master as root and would need its writable directories re-owned to match.
FROM nginxinc/nginx-unprivileged:1.30-alpine

LABEL org.opencontainers.image.title="spire-ui" \
      org.opencontainers.image.description="Code Spire dashboard — source-available, self-hosted AI code reviewer" \
      org.opencontainers.image.source="https://github.com/artyomsv/code-spire" \
      org.opencontainers.image.licenses="FSL-1.1-ALv2"

# The same build-time upgrade the service images carry, and for the same reason: nginx retags its
# own tags on its own cadence, which is slower than Alpine's package index moves. An image with no
# upgrade does not stay clean; it inherits whatever the index held when its base was last retagged.
#
# **This base is clean at the time of writing, and the first CI build with this line upgraded
# nothing but apk-tools.** That is the honest state and it is not an argument against the line: the
# two retags visible here are 2026-07-20 and 2026-09-02, six weeks apart, and the 2026-07-20 one
# carried openssl 3.5.7-r0 and libexpat 2.8.2-r0 — the exact versions the three service images were
# raising 102 alerts on. This closes that window rather than a present backlog, for about a second
# of build time.
#
# A correction worth keeping, because it is the trap this whole change is about: an earlier version
# of this comment claimed the live tag carried 24 vulnerabilities with four HIGH. It did not. That
# was measured against a **six-week-old local copy** — `docker run` and `docker build` resolve a
# floating tag from the local store before the registry, so a scan of `:1.30-alpine` reports on
# whatever was pulled last, not on what the tag points at. Pull first, then measure. Re-measured
# after a pull, both `1.30-alpine` and `1.31-alpine` score 0.
#
# The upgrade moves OS packages only. **nginx itself stays 1.30.4** — Alpine 3.24's index holds that
# version, so this can never drag the image onto nginx's mainline branch as a side effect.
#
# root for the upgrade and straight back to 101: this base runs unprivileged by design, and every
# layer after this one must land exactly as it did before.

USER root
ARG APK_UPGRADE_BUST=local
RUN echo "apk upgrade for build ${APK_UPGRADE_BUST}" && apk --no-cache upgrade
USER 101


# The filter admits only SPIRE_* and NGINX_LOCAL_RESOLVERS, so nginx's own $host, $scheme and
# $http_* survive. Without it envsubst would blank every nginx variable whose name happens to match
# an environment variable. NGINX_LOCAL_RESOLVERS is set from /etc/resolv.conf by the image's own
# entrypoint script, which is what lets the template ask for request-time DNS without knowing whether
# it is running under Docker's embedded resolver or a cluster's.
#
# The three upstream defaults are in-network service names — wiring, not credentials or
# environment-specific URLs — and every deploy artifact overrides them.
# NGINX_ENTRYPOINT_LOCAL_RESOLVERS is the opt-in switch for 15-local-resolvers.envsh, which returns
# early without it; only when set does it export NGINX_LOCAL_RESOLVERS for the filter to pick up.
#
# SPIRE_PUBLIC_HOST pins the origin OIDC builds redirect_uri from. Empty means unpinned, which is
# what every deploy artifact here relies on — a compose stack answers on localhost, a LAN address and
# a container name alike, and a Kubernetes probe addresses the pod IP. Set it to the host[:port] the
# browser uses to stop a spoofed Host reaching the login redirect. Declared even though it is empty:
# the entrypoint substitutes only variables that are DEFINED, so leaving it out would put the literal
# ${SPIRE_PUBLIC_HOST} into the config and nginx would refuse to start. See nginx/default.conf.template.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER='^(SPIRE_|NGINX_LOCAL_RESOLVERS)' \
    SPIRE_ORCHESTRATOR_URL=http://orchestrator:8080 \
    SPIRE_GATEWAY_URL=http://gateway:8080 \
    SPIRE_WORKER_URL=http://worker:8080 \
    SPIRE_PUBLIC_HOST=

COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
COPY LICENSE /LICENSE

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/healthz || exit 1
