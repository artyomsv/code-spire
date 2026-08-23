# TLS

> **Any deployment reachable from a network you do not control needs TLS.** Operator sessions are
> cookies. In plaintext they are sniffable and **replayable** — authentication stops casual and
> unauthorised access, it does not stop an on-path attacker.

## Code Spire does not terminate TLS, and will not

This is a decision, not a gap. Termination is the most environment-specific part of a deployment:
a k3s cluster with no ingress controller reaches for a standalone proxy, a managed cluster already
runs cert-manager, a single VPS wants ACME built into the proxy, and a corporate network terminates
at a load balancer nobody deploying this application controls. Every one of those operators already
has a way to do it, and a bundled terminator would be a component each of them has to work *around*
rather than with — an extra hop, an extra certificate store, an extra thing to renew.

What Code Spire does instead is be **correct behind any of them**. The requirements below are what
the application needs from a terminator; satisfy them and the topology is your choice.

## The contract a terminator must satisfy

Five requirements. Each one fails silently when it is missed — except requirement 4, which is loud by
design — which is why they are written out rather than left to a diagram.

### 1. Send `X-Forwarded-Proto: https`

The dashboard's nginx **never overwrites** a value that arrived
(`spire-ui/nginx/default.conf.template`). Behind a terminator that forwards over plain HTTP,
nginx's own `$scheme` is `http`, so deriving from it would replace your `https` with `http`.

Note what it does when the header is *absent*: the map falls back to `$scheme`, which behind a
plaintext hop is always `http`. So a terminator that omits the header does not leave it unset
downstream — it produces a definite, wrong `http`. There is no "missing header" state for a service
to detect.

When the services see `http`, OIDC mints an `http://` `redirect_uri`, and your identity provider
rejects it as unregistered (see requirement 5). The session cookie is unaffected: all three services
set `cookie-force-secure: true` under `%prod`, so `Secure` is applied regardless of what this header
says. That is deliberate — the attribute must not depend on a header a proxy might drop.

Most terminators set this header by default; confirm it rather than assume it.

### 2. Preserve the `Host` header

OIDC derives `redirect_uri` from `Host`. Rewriting it sends the login round-trip to a backend address
instead of your origin, and the callback never arrives. nginx proxy manager, Caddy and Traefik all
preserve `Host` by default; some cloud load balancers do not.

### 3. Do not publish any plaintext port beside the terminator

The passthrough in requirement 1 trusts whatever `X-Forwarded-Proto` arrives. That is correct when
the only route in is the terminator, and meaningless if the dashboard's own port is reachable too.
Bind it to the terminator's network, or to `127.0.0.1` when both run on one host.

The Compose file publishes exactly one *application* port for this reason — the dashboard's. No
Code Spire service port is published.

**It does publish a second port you must deal with: Keycloak's**, on `${SPIRE_KEYCLOAK_PORT:-34767}`,
in plain HTTP on all interfaces. That port carries every operator login and the Keycloak admin
console. If you use the bundled identity provider for anything beyond a localhost evaluation, it
needs the same treatment as the dashboard — behind the terminator, or not published at all. See
requirement 5.

### 4. Set `SPIRE_TRUSTED_PROXIES` to the dashboard's network

In `%prod` the services believe `X-Forwarded-For` and `X-Forwarded-Proto`. Without this setting they
would believe them from *any* source that can reach their port, so they refuse to start rather than
run that way. There is no default: the value is topology-specific.

This names the **dashboard**, not your external terminator. The chain is
`terminator → dashboard nginx → services`, and each service checks the address of its immediate
peer, which is always the dashboard container. Your terminator's `X-Forwarded-Proto` reaches the
services *through* nginx, which is why requirement 1 is about passthrough rather than about trust.

The right value depends on where the dashboard runs:

| Environment | Value | How to find it |
|---|---|---|
| Docker Compose | the Compose network's subnet | `docker network inspect <project>_default` — do not assume `172.16.0.0/12`; Docker allocates from other pools once its default range is used up |
| Kubernetes | the cluster's **pod CIDR** | k3s defaults to `10.42.0.0/16`; check your distribution |

**Unset is loud, wrong is silent.** The startup refusal only triggers when the value is blank. A
value that simply does not cover the dashboard's actual address leaves the services quietly
distrusting the headers, which produces an `http://` `redirect_uri` — requirement 1's failure, from a
different cause. Carrying a Docker value into a Kubernetes install is the easy way to hit this.

### 5. Give the identity provider the same origin treatment

**This is the requirement most likely to be missed, because the login round-trip's first hop leaves
Code Spire entirely.** Putting the dashboard behind TLS is not enough: the browser is redirected to
your identity provider's own origin, and that leg has to work too.

Three things have to be true:

1. **The IdP is reachable from the browser** at the address it advertises. The bundled Keycloak pins
   `KC_HOSTNAME` to `http://host.docker.internal:34767`, which no remote browser can resolve. Any
   non-localhost deployment must re-point it.
2. **The IdP is itself behind TLS.** It carries operator passwords. Re-pointing `KC_HOSTNAME` at a
   LAN or VPS address without TLS puts those passwords in plaintext — the exact threat this document
   opens with.
3. **Your `https` origin's callbacks are registered.** The shipped realm
   (`deploy/keycloak/realm-spire.json`) registers **only `http://localhost:*`** redirect URIs, for
   all three clients. A deployment at `https://spire.example.com` must register
   `https://spire.example.com/api/auth/callback`, `…/gw/auth/callback` and `…/wk/auth/callback`, plus
   the matching post-logout URIs — or Keycloak rejects the login with all four other requirements
   correctly satisfied.

If you use an existing corporate IdP rather than the bundled one, only point 3 is yours to do.

## Topology A — localhost, no TLS

For evaluation and local development only.

```bash
docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env up -d
# dashboard on http://localhost:34700
```

This works without TLS, and it is worth knowing *why*, because the reason does not travel.

Browsers treat `http://localhost`, `http://127.0.0.1` and `http://[::1]` as **potentially trustworthy
origins** (W3C Secure Contexts), and accept a `Secure` cookie there over plain HTTP. Since every
packaged run is `%prod`, which sets `cookie-force-secure: true`, each session cookie carries `Secure`
— and localhost is the one place that does not matter. Verified in current Chrome and Firefox; if you
see the trap below on localhost, check your browser's handling before assuming a deployment fault.

This also relies on the browser resolving `host.docker.internal` to reach Keycloak, which Docker
Desktop arranges and native Linux Docker does not. On Linux, re-point `KC_HOSTNAME` at
`http://localhost:34767` and register the matching realm URIs.

> ### The trap
>
> Move that identical stack to a LAN address or a VPS IP over plain HTTP and **the dashboard cannot be
> signed into**. The origin is no longer trustworthy, so the browser discards the `Secure` session
> cookie without storing it. The login round-trip completes, the redirect lands, and the dashboard
> bounces back to the login screen. Nothing logs an error — not the browser, not the service, not the
> identity provider, because from each component's own point of view nothing went wrong.
>
> A hostname does not help: only loopback literals and `localhost` (including `*.localhost`) are
> trustworthy origins, so mapping a name in `/etc/hosts` produces the same failure.
>
> If a deployment that worked on localhost stops working the moment it moves, this is almost always
> the reason, and TLS is the fix rather than a workaround.

The Helm `values-simple.yaml` preset is subject to exactly this: it renders an Ingress for
`spire.example.invalid` with `tls.enabled: false`, so its origin is a hostname rather than loopback
and **no operator can sign in**. Treat that preset as a render-and-inspect target, not a sign-in-able
deployment, unless you give it TLS or reach it over a loopback origin.

## Topology B — an external terminator

The right answer for a k3s cluster with no ingress controller, for a single VPS, and for anything
behind a corporate load balancer. Anything that satisfies the five requirements works: nginx proxy
manager, Caddy, Traefik, HAProxy, an ALB.

```
Internet
   │  :443  TLS terminated here
   ▼
[ your terminator ]
   │  :8080 http, X-Forwarded-Proto: https, Host preserved
   ▼
[ spire-ui nginx ]   ← single origin; ADR-022 cookie-path scoping lives here
   ├─→ orchestrator  /api   (+ /api/ws/*)
   ├─→ gateway       /gw    (+ /gw/ws/*) and /webhooks
   └─→ worker        /wk
```

Point the terminator at the dashboard's port and route **everything** to it. Do not split the
prefixes across backends: the four services share one origin so that each service's session cookie is
scoped to its own path and can never be sent to another. Splitting them at your terminator recreates
that routing in a second place, and any drift between the two lands on authentication, where it is
invisible until a login quietly fails.

### Reaching the dashboard from outside the cluster

On Kubernetes the chart's `spire-ui` Service is `ClusterIP`, which an external terminator cannot
reach. Two changes are needed for this topology:

- **Expose the Service.** Patch it to `NodePort` (or `LoadBalancer`), or run the terminator inside
  the cluster. The chart has no values knob for this today; a kustomize patch or
  `kubectl patch svc spire-ui -p '{"spec":{"type":"NodePort"}}'` is the direct route.
- **Turn the Ingress off** — `ingress.enabled: false`. It defaults to `true`, and with no ingress
  controller installed it renders an object nothing acts on.

Then set `trustedProxies` to the **pod CIDR** (requirement 4), not a Docker range.

### nginx proxy manager

Configured through its UI rather than a file, so as a settings list:

| Setting | Value |
|---|---|
| Scheme | `http` |
| Forward Hostname / IP | the node address (NodePort) or the dashboard host |
| Forward Port | the NodePort, or `34700` under Compose |
| Block Common Exploits | on |
| Websockets Support | **on** — required; four surfaces are WebSockets |
| SSL certificate | Let's Encrypt, or your own |
| Force SSL | on |
| HTTP/2 Support | optional |

Proxy manager sends `X-Forwarded-Proto` and preserves `Host` by default, satisfying requirements 1
and 2 with no custom configuration. Give the identity provider its own proxy host under requirement 5.

**Websockets Support is not optional.** With it off, the dashboard loads and REST works, so the
deployment looks healthy — but the timeline, the review detail, the attention panel and the gateway's
attention feed never connect, and the dashboard reports the failure as an outage of the service
behind the socket rather than as a proxy setting.

### Caddy

Caddy sets both headers and obtains certificates automatically:

```caddyfile
spire.example.com {
    reverse_proxy dashboard-host:34700
}
```

### Long-lived connections

Whatever you use, raise the read/idle timeout above its default. Those four WebSockets are idle
between events, and a 60-second default cuts them into reconnect churn.

## Topology C — Kubernetes Ingress with cert-manager

The chart **already renders** Ingress TLS. `values-production.yaml`:

```yaml
ingress:
  enabled: true
  host: spire.example.com
  tls:
    enabled: true
    secretName: spire-tls
```

What it deliberately does not do is create `spire-tls`. The chart never generates a secret — a
`randAlphaNum` idiom would rotate `SPIRE_ENCRYPTION_KEYSET` on every `helm upgrade` and make every
encrypted event payload, provider secret and context blob permanently unreadable, so the rule is
absolute rather than per-secret.

cert-manager fills it through the chart's existing annotation passthrough. **No chart change is
needed:**

```yaml
ingress:
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
```

cert-manager then issues the certificate and writes it to `spire-tls`, which the rendered Ingress
already references. Bringing your own certificate works the same way — create the secret yourself:

```bash
kubectl create secret tls spire-tls --cert=fullchain.pem --key=privkey.pem
```

Set `trustedProxies` to the cluster's pod CIDR (requirement 4), and register the origin's three
callbacks with your identity provider (requirement 5).

**Check the WebSocket timeout annotations against your controller.** The chart sets
`nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"`, which ingress-nginx honours and Traefik,
HAProxy and cloud controllers ignore. On any other class, apply that class's own equivalent or the
four live surfaces will be cut at the controller's default.

## Webhooks

SCM deliveries arrive at `/webhooks/{provider}/{key}` and route to the gateway, which is public by
policy because an SCM presents an HMAC signature rather than a session.

That signature proves **authenticity, not confidentiality**. Over plain HTTP the payload — pull
request metadata, branch names, repository paths — travels in clear, and the routing `key` in the URL
travels with it. Give the webhook endpoint the same TLS as the dashboard; it is the same origin, so
if you followed Topology B or C it already has it.

Note that webhooks carry no cookie and so are unaffected by the sign-in failures above. A plaintext
deployment can be ingesting webhooks and completing reviews while no operator can sign in to see
them.

## Verifying it worked

Run these against the deployed origin, not localhost.

**1. The session cookie is marked `Secure`.** In dev tools under Application → Cookies, look for
cookies whose name begins `q_session` and ends `_api`, `_gw` or `_wk` (Quarkus composes the name from
the tenant and the configured suffix, and splits large cookies into `…_chunk_N` parts). Each should
show `Secure` and `HttpOnly`, with `Path` = `/api`, `/gw` and `/wk` respectively. The gateway and
worker sessions appear only after the dashboard has probed those siblings.

**2. The login redirect is `https`.** Watch the network tab through a sign-in: the `redirect_uri`
query parameter on the authorization request must carry your `https://` origin. An `http://` one
means requirement 1 or 4 is unmet. A correctly-formed `https` one that the IdP still rejects means
requirement 5 — the callback is not registered.

**3. Both WebSocket prefixes upgrade.** Filter the network tab to WS. Connections to `/api/ws/*`
**and** `/gw/ws/webhook-attention` should show status 101 over `wss://`. Check the gateway one
specifically: it is the one that catches a `/gw` route that is broken while `/api` works.

**4. All three prefixes reach their service.** A bare `/api`, `/gw` or `/wk` must return something
from the service — an auth redirect or a 401 — not the dashboard's `index.html`. Getting HTML back
means the request fell through to the SPA fallback and that prefix is not routed.

## Troubleshooting

Each of these is silent in at least one component, which is why the symptom is the entry point.

| Symptom | Cause |
|---|---|
| Login completes, then bounces back to the login screen | The browser is discarding a `Secure` cookie over a non-trustworthy origin. You are on plain HTTP somewhere other than a loopback address. |
| Identity provider rejects the redirect | Either `redirect_uri` is `http://` (requirement 1 or 4), or it is correct and simply not registered for the client (requirement 5). Check which before changing proxy settings — with the bundled realm, an unregistered callback is the likelier cause. |
| Login redirects to an address the browser cannot resolve | The IdP is advertising a hostname only the containers can reach — requirement 5, point 1. |
| Login round-trip lands on a backend port | `Host` was rewritten — requirement 2. |
| Services refuse to start | `SPIRE_TRUSTED_PROXIES` is unset — requirement 4. This one is loud, by design. |
| Everything starts, but `redirect_uri` is `http://` anyway | `SPIRE_TRUSTED_PROXIES` is *set but wrong* — it does not cover the dashboard's actual address, so the headers are distrusted. A Docker range carried into Kubernetes does this. |
| Dashboard loads, REST works, no live updates | The terminator is not upgrading WebSockets. |
| Live updates connect then drop every minute | The terminator's read timeout is at its default, or its Ingress class ignores the chart's nginx-specific annotation. |
| Webhooks screen says "failed to fetch" while reviews work | `/gw` is not routed to the gateway, or is routed somewhere other than through the single origin. |
| SCM reports a successful delivery but no review starts | `/webhooks` is falling through to the SPA, which answers 200 with `index.html`. It must be routed to the gateway ahead of the fallback. |

## Related

- `docs/SECURITY.md` — trust boundaries and the threat model TLS sits inside
- `docs/DECISIONS.md` ADR-022 — why the four services share one origin, and why each session is
  scoped to its own URL prefix
- `deploy/README.md` — the deployment topologies these apply to
- `spire-ui/nginx/default.conf.template` — the single-origin routing itself, with each rule's reason
