# The egress proxy: a run unit's agent reaches the network through this and nothing else
# (docs/superpowers/plans/2026-11-01-harness-reviews-2-run-egress.md, docs/SECURITY.md).
#
#   ./deploy/egress/build.sh                                    # default tag: spire-egress-proxy:latest
#   docker build -t spire-egress-proxy:latest deploy/egress     # the same image
#
# tinyproxy and nothing else. The image carries no policy of its own: the runtime renders the
# configuration for each run (report or enforce, the declared hosts, the corporate upstream) and hands
# it over as two variables, which the command below writes to /tmp before starting the proxy. /tmp is
# the container's only writable path; the runtime mounts the root filesystem read-only.
FROM alpine:3.20

# APK_UPGRADE_BUST for the reason the root Dockerfile records: a RUN with no changing input caches on
# the base image alone, so the upgrade would run once. The value is echoed because BuildKit keys a RUN
# only on the arguments it references.
ARG APK_UPGRADE_BUST=local
RUN echo "apk upgrade for build ${APK_UPGRADE_BUST}" \
    && apk --no-cache upgrade \
    && apk add --no-cache tinyproxy \
    && adduser -D -H -u 1002 egress

USER 1002:1002
EXPOSE 8888

# One shell line rather than a script file: a script checked out with CRLF on Windows would not run,
# and this line has no file to get wrong. -d keeps tinyproxy in the foreground, where it is PID 1.
CMD ["/bin/sh", "-c", "printf '%s\\n' \"$SPIRE_EGRESS_CONF\" > /tmp/tinyproxy.conf && printf '%s\\n' \"$SPIRE_EGRESS_FILTER\" > /tmp/filter && exec tinyproxy -d -c /tmp/tinyproxy.conf"]
