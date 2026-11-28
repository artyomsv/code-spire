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
    cp /etc/tinyproxy/tinyproxy.conf /tmp/stock.conf; timeout 1 tinyproxy -d -c /tmp/stock.conf >/dev/null 2>&1; echo "stock copy in tmp: exit $?" >&2
    echo "Port 8888" > /tmp/p8888.conf; timeout 1 tinyproxy -d -c /tmp/p8888.conf >/dev/null 2>&1; echo "p8888: exit $?" >&2
    printf 'Port 8889\nUser egress\n' > /tmp/pu.conf; timeout 1 tinyproxy -d -c /tmp/pu.conf >/dev/null 2>&1; echo "port+user: exit $?" >&2
    : > /tmp/empty.conf; timeout 1 tinyproxy -d -c /tmp/empty.conf >/dev/null 2>&1; echo "empty: exit $?" >&2
    timeout 1 tinyproxy -d -c /etc/tinyproxy/tinyproxy.conf >/dev/null 2>&1; echo "stock: exit $?" >&2
    ls -ld /tmp /dev/shm /etc/tinyproxy >&2
    cp /tmp/p8888.conf /dev/shm/a.conf; timeout 1 tinyproxy -d -c /dev/shm/a.conf >/dev/null 2>&1; echo "dev/shm: exit $?" >&2
    (cd /tmp; timeout 1 tinyproxy -d -c p8888.conf >/dev/null 2>&1; echo "relative in tmp: exit $?" >&2)
    timeout 1 tinyproxy -d -c /proc/self/fd/0 < /tmp/p8888.conf >/dev/null 2>&1; echo "via stdin fd: exit $?" >&2
    timeout 1 tinyproxy -d -c /tmp/p8888.conf 2>&1 | head -5 >&2
    cat /tmp/p8888.conf >&2
    chmod 444 /tmp/p8888.conf; timeout 1 tinyproxy -d -c /tmp/p8888.conf >/dev/null 2>&1; echo "chmod 444: exit $?" >&2
    chmod 400 /tmp/p8888.conf; timeout 1 tinyproxy -d -c /tmp/p8888.conf >/dev/null 2>&1; echo "chmod 400: exit $?" >&2
    echo "Port 8888" > /tmp/q.conf; chmod 444 /tmp/q.conf; timeout 1 tinyproxy -d -c /tmp/q.conf >/dev/null 2>&1; echo "fresh 444: exit $?" >&2
    cat /tmp/p8888.conf | timeout 1 tinyproxy -d -c /dev/stdin >/dev/null 2>&1; echo "pipe stdin: exit $?" >&2
    timeout 1 tinyproxy -d -c /dev/stdin < /etc/tinyproxy/tinyproxy.conf >/dev/null 2>&1; echo "stock via stdin redirect: exit $?" >&2
    (cd /etc/tinyproxy && timeout 1 tinyproxy -d -c tinyproxy.conf >/dev/null 2>&1; echo "stock relative: exit $?" >&2)
    ln -s /etc/tinyproxy/tinyproxy.conf /tmp/link.conf; timeout 1 tinyproxy -d -c /tmp/link.conf >/dev/null 2>&1; echo "symlink: exit $?" >&2
    cat /etc/tinyproxy/tinyproxy.conf > /tmp/big.conf; cmp /tmp/big.conf /etc/tinyproxy/tinyproxy.conf && echo identical >&2
    timeout 1 tinyproxy -d -c /tmp/big.conf >/dev/null 2>&1; echo "cat copy: exit $?" >&2
    grep -v '^#' /etc/tinyproxy/tinyproxy.conf | grep -v '^$' >&2
    timeout 1 tinyproxy -d -c /tmp/q.conf; echo "fresh 444 visible: exit $?" >&2
    echo "Port 8889" > /tmp/min.conf; timeout 1 tinyproxy -d -c /tmp/min.conf >/dev/null 2>&1; echo "min: exit $?" >&2
    exit 1
}

# The close: one connection ends the wait. nc -l serves a single connection and returns when it ends;
# timeout ends it at the deadline.
timeout "$deadline" nc -l -p "$close_port" </dev/null >/dev/null 2>&1

kill "$proxy_pid" 2>/dev/null
wait "$proxy_pid" 2>/dev/null
exit 0
