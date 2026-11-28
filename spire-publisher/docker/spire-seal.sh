#!/bin/sh
# The Kubernetes arm: measure that the pod network is closed before the agent starts (SealMain).
exec java -cp '/opt/spire-publisher/lib/*' dev.codespire.publisher.SealMain "$@"
