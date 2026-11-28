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

# spire-tinyproxy is tinyproxy under a path no host AppArmor profile names (see the Dockerfile).
spire-tinyproxy -d -c /tmp/tinyproxy.conf &
proxy_pid=$!

# Fail at once, with the proxy's own message above in the log, if tinyproxy did not stay up.
sleep 1
if ! kill -0 "$proxy_pid" 2>/dev/null; then
    echo "spire-fetch-proxy: tinyproxy exited at start" >&2
    exit 1
fi

# The close: one connection ends the wait. nc -l accepts it, -e /bin/true answers by closing it at once (without -e
# nc would wait for the client to hang up), and nc returns. timeout ends the wait at the deadline.
timeout "$deadline" nc -l -p "$close_port" -e /bin/true </dev/null >/dev/null

kill "$proxy_pid" 2>/dev/null
wait "$proxy_pid" 2>/dev/null
exit 0
