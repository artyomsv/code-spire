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

Four requirements. Each one fails silently when it is missed, which is why they are written out
rather than left to a diagram.

### 1. Send `X-Forwarded-Proto: https`

The dashboard's nginx passes this header through and **never derives it from `$scheme`**
(`spire-ui/nginx/default.conf.template`). Behind a terminator that forwards over plain HTTP,
`$scheme` is `http`, so deriving it would overwrite your `https` with `http`.

When the services see `http`, two things break together: OIDC mints an `http://` `redirect_uri` that
the realm rejects, and the session cookie loses its `Secure` attribute. Most terminators set this
header by default; confirm it rather than assume it.

### 2. Preserve the `Host` header

OIDC derives `redirect_uri` from `Host`. Rewriting it sends the login round-trip to a backend address
instead of your origin, and the callback never arrives. nginx proxy manager, Caddy and Traefik all
preserve `Host` by default; some cloud load balancers do not.

### 3. Do not publish the dashboard port alongside the terminator

The passthrough in requirement 1 trusts whatever `X-Forwarded-Proto` arrives. That is correct when
the only route in is the terminator, and meaningless if the dashboard's own port is reachable too.
Bind it to the terminator's network, or to `127.0.0.1` when both run on one host.

The Compose file publishes exactly one application port for this reason — the dashboard's. No
service port is published, which is what makes the next requirement meaningful.

### 4. Set `SPIRE_TRUSTED_PROXIES` to the dashboard container's network

In `%prod` the services believe `X-Forwarded-For` and `X-Forwarded-Proto`. Without this setting they
would believe them from *any* source that can reach their port, so they refuse to start rather than
run that way. There is no default: the value is topology-specific. Docker's default bridge pool is
`172.16.0.0/12`.

Note this names the **dashboard**, not your external terminator. The chain is
`terminator → dashboard nginx → services`, and the services' trust boundary ends at the dashboard.
Your terminator's `X-Forwarded-Proto` reaches them *through* nginx, which is why requirement 1 is
about passthrough rather than about trust.

## Topology A — localhost, no TLS

For evaluation and local development only.

```bash
docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env up -d
# dashboard on http://localhost:34700
```

This works without TLS, and it is worth knowing *why*, because the reason does not travel.

Browsers treat `http://localhost`, `http://127.0.0.1` and `http://[::1]` as **potentially trustworthy
origins** (W3C Secure Contexts). A cookie marked `Secure` is therefore accepted and stored over plain
HTTP on those origins alone. Since Compose runs `QUARKUS_PROFILE=prod`, which sets
`cookie-force-secure: true`, every session cookie carries `Secure` — and localhost is the one place
that does not matter.

> ### The trap
>
> Move that identical stack to a LAN address or a VPS IP over plain HTTP and **login silently fails**.
> The origin is no longer trustworthy, so the browser discards the `Secure` session cookie without
> storing it. The login round-trip completes, the redirect lands, and the dashboard bounces back to
> the login screen. Nothing logs an error — not the browser, not the service, not the identity
> provider, because from each component's own point of view nothing went wrong.
>
> If a deployment that worked on localhost stops working the moment it moves, this is almost always
> the reason, and TLS is the fix rather than a workaround.

The Helm `values-simple.yaml` preset renders an Ingress with `tls.enabled: false` for the same
evaluation case, and carries the same warning.

## Topology B — an external terminator

The right answer for a k3s cluster with no ingress controller, for a single VPS, and for anything
behind a corporate load balancer. Anything that satisfies the four requirements works: nginx proxy
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

### nginx proxy manager

Configured through its UI rather than a file, so as a settings list:

| Setting | Value |
|---|---|
| Scheme | `http` |
| Forward Hostname / IP | the dashboard container or node address |
| Forward Port | `34700` (Compose) or the `spire-ui` service port |
| Block Common Exploits | on |
| Websockets Support | **on** — required; four surfaces are WebSockets |
| SSL certificate | Let's Encrypt, or your own |
| Force SSL | on |
| HTTP/2 Support | optional |

Proxy manager sends `X-Forwarded-Proto` and preserves `Host` by default, satisfying requirements 1
and 2 with no custom configuration.

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
between events, and a 60-second default cuts them into reconnect churn. The Helm chart already
annotates its Ingress with `proxy-read-timeout: 3600`; an external terminator needs the equivalent.

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

## Webhooks

SCM deliveries arrive at `/webhooks/{provider}/{key}` and route to the gateway, which is public by
policy because an SCM presents an HMAC signature rather than a session.

That signature proves **authenticity, not confidentiality**. Over plain HTTP the payload — pull
request metadata, branch names, repository paths — travels in clear, and the routing `key` in the URL
travels with it. Give the webhook endpoint the same TLS as the dashboard; it is the same origin, so
if you followed Topology B or C it already has it.

## Verifying it worked

Four checks. Run them against the deployed origin, not localhost.

**1. The session cookie is marked `Secure`.** In the browser's dev tools, under Application →
Cookies, `q_session_api` should show `Secure` and `HttpOnly`, with `Path` = `/api`. The gateway and
worker sessions appear as `q_session_gw` (`/gw`) and `q_session_wk` (`/wk`) once the dashboard has
probed them.

**2. The login redirect is `https`.** Watch the network tab through a sign-in: the `redirect_uri`
query parameter on the authorization request must carry your `https://` origin. An `http://` one
means requirement 1 is unmet, and the realm will reject it.

**3. WebSockets upgrade.** Filter the network tab to WS. Connections to `/api/ws/*` should show
status 101 with a `wss://` URL. If REST works and these do not, start with the terminator's WebSocket
setting.

**4. All three prefixes answer.** `/api/me`, `/gw` and `/wk` must all reach their service through the
one origin. The dashboard probes the siblings after sign-in, so a Webhooks screen that reports
"failed to fetch" while the reviews list works means one prefix is not routed.

## Troubleshooting

Each of these is silent in at least one component, which is why the symptom is the entry point.

| Symptom | Cause |
|---|---|
| Login completes, then bounces back to the login screen | The browser is discarding a `Secure` cookie over a non-trustworthy origin. You are on plain HTTP somewhere other than localhost. |
| Identity provider rejects the redirect | `redirect_uri` is `http://` — requirement 1. The realm cannot enumerate a URI it was not registered with. |
| Login round-trip lands on a backend port | `Host` was rewritten — requirement 2. |
| Services refuse to start | `SPIRE_TRUSTED_PROXIES` is unset — requirement 4. This one is loud, by design. |
| Dashboard loads, REST works, no live updates | The terminator is not upgrading WebSockets. |
| Live updates connect then drop every minute | The terminator's read timeout is at its default. Raise it. |
| Webhooks screen says "failed to fetch" while reviews work | `/gw` is not routed to the gateway, or is routed somewhere other than through the single origin. |
| SCM reports a successful delivery but no review starts | `/webhooks` is falling through to the SPA, which answers 200 with `index.html`. It must be routed to the gateway ahead of the fallback. |

## Related

- `docs/SECURITY.md` — trust boundaries and the threat model TLS sits inside
- `docs/DECISIONS.md` ADR-022 — why the four services share one origin, and why each session is
  scoped to its own URL prefix
- `deploy/README.md` — the deployment topologies these apply to
- `spire-ui/nginx/default.conf.template` — the single-origin routing itself, with each rule's reason
