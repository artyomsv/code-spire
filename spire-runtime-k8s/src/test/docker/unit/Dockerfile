# spire-test-unit:TEST -- the one image the Kubernetes arm's cluster tests run in their pods.
# Alpine ships busybox nc and wget; the user is the same non-root uid the run unit images use.
FROM alpine:3.22
RUN adduser -D -u 1001 unit
USER 1001
