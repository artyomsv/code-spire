# syntax=docker/dockerfile:1
#
# The dashboard image: static assets AND the reverse proxy that puts all four services on one origin.
# That proxy is what makes ADR-022's cookie-path scoping real in a packaged run — see
# nginx/default.conf.template for why each rule is the way it is.
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
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1 \
    NGINX_ENVSUBST_FILTER='^(SPIRE_|NGINX_LOCAL_RESOLVERS)' \
    SPIRE_ORCHESTRATOR_URL=http://orchestrator:8080 \
    SPIRE_GATEWAY_URL=http://gateway:8080 \
    SPIRE_WORKER_URL=http://worker:8080

COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
COPY LICENSE /LICENSE

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/healthz || exit 1
