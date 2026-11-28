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
kill -0 "$proxy_pid" 2>/dev/null || {
    echo "tinyproxy exited early" >&2
    n=$(wc -l < /tmp/tinyproxy.conf)
    i=1
    while [ "$i" -le "$n" ]; do
        sed "${i}d" /tmp/tinyproxy.conf > /tmp/t$i.conf
        timeout 2 tinyproxy -d -c /tmp/t$i.conf >/dev/null 2>&1
        echo "without line $i ($(sed -n "${i}p" /tmp/tinyproxy.conf)): exit $?" >&2
        i=$((i+1))
    done
    timeout 2 tinyproxy -d -c /tmp/tinyproxy.conf; echo "full: exit $?" >&2
    echo "Port 8889" > /tmp/min.conf; timeout 2 tinyproxy -d -c /tmp/min.conf >/dev/null 2>&1; echo "min: exit $?" >&2
    ls -ln /dev/null /dev/stdout /dev/stderr /etc/tinyproxy 2>&1 | head -12 >&2
    grep -E "Cap|Seccomp|NoNewPrivs" /proc/self/status >&2
    timeout 2 tinyproxy -d -c /etc/tinyproxy/tinyproxy.conf; echo "stock conf: exit $?" >&2
    timeout 2 tinyproxy -d -c /tmp/min.conf </dev/null; echo "min with stdin null: exit $?" >&2
    cat /tmp/min.conf | head -3 >&2
    HOME=/tmp timeout 2 tinyproxy -d -c /tmp/min.conf; echo "min HOME=/tmp: exit $?" >&2
    timeout 2 tinyproxy -v >&2
    exit 1
}

# The close: one connection ends the wait. nc -l serves a single connection and returns when it ends;
# timeout ends it at the deadline.
timeout "$deadline" nc -l -p "$close_port" </dev/null >/dev/null 2>&1

kill "$proxy_pid" 2>/dev/null
wait "$proxy_pid" 2>/dev/null
exit 0
