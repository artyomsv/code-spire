#!/bin/sh
# The sidecar's entrypoint: watch /handoff, gate, push (PublisherMain).
exec java -cp '/opt/spire-publisher/lib/*' dev.codespire.publisher.PublisherMain "$@"
