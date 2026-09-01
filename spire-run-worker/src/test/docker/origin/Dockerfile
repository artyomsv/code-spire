# A real git remote for the M0 exit-criteria test: smart HTTP with basic auth, seeded with one
# commit on main. Test-only. Nothing here is a pattern for production — it runs as root and serves
# plaintext on the Docker network, exactly as far as a test needs and no further.
FROM alpine:3.20
RUN apk add --no-cache git git-daemon nginx fcgiwrap spawn-fcgi openssl
COPY nginx.conf /etc/nginx/nginx.conf
COPY --chmod=755 entrypoint.sh /usr/local/bin/origin
EXPOSE 80
ENTRYPOINT ["/usr/local/bin/origin"]
