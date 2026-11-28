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
# nc would wait for the client to hang up), and nc returns. timeout ends the wait at the deadline. -s 0.0.0.0: busybox
# nc listens on IPv6 only without it, and a client dials the pod's IPv4 address (netstat showed :::8890 alone).
( sleep 3; netstat -ltn >&2; ps >&2 ) &
timeout "$deadline" nc -l -s 0.0.0.0 -p "$close_port" -e /bin/true </dev/null >/dev/null
wait_status=$?
echo "spire-fetch-proxy: the wait ended with status $wait_status; stopping tinyproxy" >&2

kill "$proxy_pid" 2>/dev/null
wait "$proxy_pid" 2>/dev/null
echo "spire-fetch-proxy: tinyproxy stopped" >&2
exit 0
