# spire-test-stubborn-egress:TEST -- the egress image with a fetch proxy that never closes: it accepts the close
# connection and keeps answering on the proxy port. The unit's fetch-closed check must then fail the run before
# any agent starts (M5-AC-23). Built FROM the locally built spire-egress-proxy:TEST, so the egress proxy itself is
# the real one. The script is written here, not copied, so no checkout can give it CRLF line ends.
FROM spire-egress-proxy:TEST
USER root
RUN printf '%s\n' '#!/bin/sh' \
        'nc -ll -p "$1" -e /bin/true &' \
        'exec nc -ll -p 8889 -e /bin/true' > /usr/local/bin/spire-fetch-proxy \
    && chmod 755 /usr/local/bin/spire-fetch-proxy
USER 1002:1002
