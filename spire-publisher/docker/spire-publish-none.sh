#!/bin/sh
# A specification or plan run's sidecar: wait for DONE, report the handoff files, push nothing (ReadOnlyPublisherMain).
exec java -cp '/opt/spire-publisher/lib/*' dev.codespire.publisher.ReadOnlyPublisherMain "$@"
