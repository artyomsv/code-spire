#!/bin/sh
# The Kubernetes arm: close the fetch proxy and prove it is closed (FetchClosedMain).
exec java -cp '/opt/spire-publisher/lib/*' dev.codespire.publisher.FetchClosedMain "$@"
