#!/usr/bin/env bash
#
# Builds the egress proxy image a run unit's agent reaches the network through.
#
#   ./deploy/egress/build.sh [tag]        # default tag: spire-egress-proxy:latest
#
# The run worker names the image in spire.run.egress.image (SPIRE_RUN_EGRESS_IMAGE), default
# spire-egress-proxy:latest. Like the publisher image, it is not on GHCR yet and is built here.
set -euo pipefail

TAG="${1:-spire-egress-proxy:latest}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

docker build -t "$TAG" "$HERE"
echo "==> built $TAG"
