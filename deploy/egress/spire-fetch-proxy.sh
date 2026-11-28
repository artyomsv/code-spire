#!/bin/sh
# A fetch proxy that closes for good: the Kubernetes unit's init clones through it, then the unit's
# spire-fetch-closed container connects to the close port, and the agent never sees the forge.
#
#   spire-fetch-proxy <close-port> <deadline-seconds>
#
# Writes the tinyproxy files exactly as the image's CMD does, opens the close port, starts tinyproxy, waits
# for ONE TCP connection on <close-port> or for the deadline, stops tinyproxy and exits 0. SIGTERM stops
# tinyproxy and exits 0 at once. Exits 1 if the close port cannot be opened or tinyproxy does not stay up.
# POSIX sh and busybox only (nc, timeout, netstat, pkill).
set -u

if [ "$#" -ne 2 ]; then
    echo "usage: spire-fetch-proxy <close-port> <deadline-seconds>" >&2
    exit 2
fi
close_port=$1
deadline=$2
listener_pid=""
proxy_pid=""

# A finished child stays a zombie until the shell waits for it, and kill -0 still succeeds on a zombie.
alive() {
    [ -r "/proc/$1/status" ] && ! grep -q "^State:.*Z" "/proc/$1/status"
}

stop() {
    if [ -n "$listener_pid" ]; then
        pkill -P "$listener_pid" 2>/dev/null
        kill "$listener_pid" 2>/dev/null
    fi
    if [ -n "$proxy_pid" ]; then
        kill "$proxy_pid" 2>/dev/null
        wait "$proxy_pid" 2>/dev/null
    fi
}
trap 'stop; exit 0' TERM INT

printf '%s\n' "$SPIRE_EGRESS_CONF" > /tmp/tinyproxy.conf
printf '%s\n' "$SPIRE_EGRESS_FILTER" > /tmp/filter

# The close port opens BEFORE the proxy accepts, so a fetch that ends at once still finds it. One connection
# ends the wait: nc -l accepts it, -e /bin/true closes it at once (without -e nc waits for the client to hang
# up), and nc returns. timeout ends the wait at the deadline. -s 0.0.0.0: without it nc showed only :::PORT
# in netstat, and a client dials the pod's IPv4 address.
timeout "$deadline" nc -l -s 0.0.0.0 -p "$close_port" -e /bin/true </dev/null >/dev/null &
listener_pid=$!

# Do not start the proxy until the port is really listening; a bind error ends the script here.
tries=0
until netstat -ltn 2>/dev/null | grep -q "0\.0\.0\.0:$close_port "; do
    tries=$((tries + 1))
    if [ "$tries" -gt 50 ] || ! alive "$listener_pid"; then
        echo "spire-fetch-proxy: the close port $close_port did not open" >&2
        stop
        exit 1
    fi
    sleep 0.1
done

# spire-tinyproxy is tinyproxy under a path no host AppArmor profile names (see the Dockerfile).
spire-tinyproxy -d -c /tmp/tinyproxy.conf &
proxy_pid=$!

# The wait ends when the close port is connected or the deadline passes. A tinyproxy that dies meanwhile fails
# the unit at once instead of leaving it to the deadline.
while alive "$listener_pid"; do
    if ! alive "$proxy_pid"; then
        echo "spire-fetch-proxy: tinyproxy exited" >&2
        stop
        exit 1
    fi
    sleep 0.2
done
echo "spire-fetch-proxy: the wait ended; stopping tinyproxy" >&2

stop
echo "spire-fetch-proxy: tinyproxy stopped" >&2
exit 0
