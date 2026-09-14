#!/bin/sh
exec java -cp '/opt/spire-publisher/lib/*' dev.codespire.publisher.HeldPublisherMain "$@"
