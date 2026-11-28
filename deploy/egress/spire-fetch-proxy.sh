#!/bin/sh
# A fetch proxy that closes for good: the Kubernetes unit's init clones through it, then the unit's
# spire-fetch-closed container connects to the close port, and the agent never sees the forge.
#
#   spire-fetch-proxy <close-port> <deadline-seconds>
#
# Writes the tinyproxy files exactly as the image's CMD does, starts tinyproxy, waits for ONE TCP connection
# on <close-port> or for the deadline, stops tinyproxy and exits 0. POSIX sh and busybox only (nc, timeout).
set -u

if [ "$#" -ne 2 ]; then
    echo "usage: spire-fetch-proxy <close-port> <deadline-seconds>" >&2
    exit 2
fi
close_port=$1
deadline=$2

printf '%s\n' "$SPIRE_EGRESS_CONF" > /tmp/tinyproxy.conf
printf '%s\n' "$SPIRE_EGRESS_FILTER" > /tmp/filter

tinyproxy -d -c /tmp/tinyproxy.conf &
proxy_pid=$!

# TEMP-DIAGNOSTIC
sleep 1
kill -0 "$proxy_pid" 2>/dev/null || { echo "tinyproxy exited early" >&2; id >&2; ls -ln /tmp >&2; cat /tmp/tinyproxy.conf >&2; exit 1; }

# The close: one connection ends the wait. nc -l serves a single connection and returns when it ends;
# timeout ends it at the deadline.
timeout "$deadline" nc -l -p "$close_port" </dev/null >/dev/null 2>&1

kill "$proxy_pid" 2>/dev/null
wait "$proxy_pid" 2>/dev/null
exit 0
