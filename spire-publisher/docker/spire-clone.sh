#!/bin/sh
# The init container's entrypoint: populate /workspace and exit (CloneMain).
exec java -cp '/opt/spire-publisher/lib/*' dev.codespire.publisher.CloneMain "$@"
