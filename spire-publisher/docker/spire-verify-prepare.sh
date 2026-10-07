#!/bin/sh
# The verify unit's init container: rebuild the held checkpoint in a fresh workspace and exit (VerifyPrepareMain).
exec java -cp '/opt/spire-publisher/lib/*' dev.codespire.publisher.VerifyPrepareMain "$@"
