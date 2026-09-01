#!/bin/sh
# Seeds /srv/git/app.git with one commit on main (a README and one workflow file, so a run can
# both add and edit a CI path) and serves it over smart HTTP behind basic auth for ORIGIN_USER.
set -eu
: "${ORIGIN_USER:?ORIGIN_USER is required}" "${ORIGIN_SECRET:?ORIGIN_SECRET is required}"

printf '%s:%s\n' "$ORIGIN_USER" "$(openssl passwd -apr1 "$ORIGIN_SECRET")" > /srv/htpasswd
mkdir -p /srv/git /run
git init -q --bare /srv/git/app.git
git -C /srv/git/app.git config http.receivepack true

seed="$(mktemp -d)"
git init -q -b main "$seed"
cd "$seed"
git config user.name seed
git config user.email seed@factory.invalid
printf 'seed\n' > README.md
mkdir -p .github/workflows
printf 'name: ci\non: push\n' > .github/workflows/ci.yml
git add -A
git commit -q -m "seed"
git push -q /srv/git/app.git main
cd /
rm -rf "$seed"
git -C /srv/git/app.git symbolic-ref HEAD refs/heads/main

spawn-fcgi -s /run/fcgiwrap.sock -M 666 -- /usr/bin/fcgiwrap
exec nginx -c /etc/nginx/nginx.conf -g 'daemon off;'
