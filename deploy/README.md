# Deploying Code Spire

> ## Put TLS in front of this
>
> Code Spire terminates no TLS, by design — see [`docs/TLS.md`](../docs/TLS.md) for the five
> requirements a terminator must satisfy and three worked topologies (localhost, an external proxy
> such as nginx proxy manager or Caddy, and a Kubernetes Ingress with cert-manager).
>
> Operator sessions are cookies, and in plaintext they are sniffable and **replayable** —
> authentication stops casual and unauthorised access, it does not stop an on-path attacker. It is
> also not only an encryption question: beyond a loopback origin, **no operator can sign in to a
> plaintext deployment at all**, because the session cookie is marked `Secure` and the browser
> discards it. Login then fails silently, with no error in the browser, the service or the identity
> provider. Webhook ingestion and bearer-token API access are unaffected, so a deployment can be
> running reviews that nobody can log in to see.
>
> The identity provider needs the same treatment, and the bundled Keycloak is published in plaintext
> on its own port and registers only `localhost` callbacks — `docs/TLS.md` requirement 5.
>
> This is a deployment requirement, not an optional hardening step.

Code Spire is **source-available**, not open source, and licensed per module — Apache-2.0 for the
plugin SPI, libraries and reference adapters; FSL-1.1-ALv2 for the four deployables published here.
See [`LICENSING.md`](../LICENSING.md).

## One command

```bash
cp deploy/.env.example deploy/.env      # then fill it in — every value is required
docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env up -d
```

The dashboard is then on `http://localhost:34700`. Sign in with the realm's dev users; the bundled
Keycloak's own admin console is on `http://localhost:34767`.

To build from a checkout instead of pulling published images, use `deploy/compose.yml` with
`--build`. Same topology, so what you verify locally is what a published install does.

## What runs

| Service | Role |
|---|---|
| `ui` | **The only published port.** Serves the dashboard *and* reverse-proxies `/webhooks`, `/api`, `/gw`, `/wk` |
| `orchestrator` | Deciders, sagas, the event store, the operator API |
| `gateway` | The webhook edge and the per-repo webhook registry |
| `worker` | Diff, context and review work; assembled-context reads |
| `postgres`, `redpanda`, `keycloak` | Storage, the bus, and the identity provider |

**No service port is published, deliberately.** The services trust `X-Forwarded-For` and `-Proto` in
production, so anything able to reach one directly could forge its apparent client address. They
refuse to start unless `SPIRE_TRUSTED_PROXIES` names who may be believed.

### Why the dashboard is also the proxy

Each service owns one URL prefix and scopes its session cookie to that path, so one service can never
receive another's credential (ADR-022). Cookies scope by host **and** path, which only isolates
anything while all four services answer on **one origin**. The `ui` image is what produces that
origin. Two consequences worth knowing before changing anything:

- **The dashboard must sit at the origin root.** Every redirect target in the services is `/` and the
  cookie paths are absolute. There is no sub-path deployment.
- **`/webhooks` must route to the gateway.** Without that route an SCM delivery reaches the SPA
  fallback instead, every delivery fails, and no review ever starts.

## The four secrets

| Variable | What it protects |
|---|---|
| `SPIRE_ENCRYPTION_KEYSET` | Event payloads, provider credentials, assembled context |
| `SPIRE_ENCRYPTION_WEBHOOK_KEYSET` | Per-repo webhook secrets — **gateway only** |
| `SPIRE_OIDC_*_SECRET` (×3) | One per service, and they must differ |
| `POSTGRES_PASSWORD` / `GATEWAY_POSTGRES_PASSWORD` | Two roles, two scopes (below) |

Generate the two keysets with:

```bash
./gradlew -q :spire-encryption:generateKeyset
```

Run it **twice** — the gateway's keyset must be independent, so a compromised internet-facing edge
can decrypt webhook secrets and nothing else.

> **Never let a deployment tool generate these.** They decrypt data already at rest. A regenerated
> keyset makes every encrypted event payload, provider secret and assembled context permanently
> unreadable. This is why the Helm chart takes Secret *names* and never mints a value: Helm's
> `randAlphaNum` idiom is safe for shared state and catastrophic for keys to existing data.

## The gateway's database role

The gateway owns only the `gateway` schema — its webhook registry — and is never granted the
`orchestrator` schema, so a compromised edge can verify signatures but cannot read the encrypted
SCM/LLM token registry or the event store.

`infra/postgres-init` provisions it automatically **on a fresh volume only**. Against an existing or
external database, run these two statements as a superuser yourself:

```sql
CREATE ROLE "gateway" LOGIN PASSWORD '<GATEWAY_POSTGRES_PASSWORD>';
CREATE SCHEMA IF NOT EXISTS gateway AUTHORIZATION "gateway";
```

Do **not** widen this with a broad `GRANT`, and do not grant the gateway role membership in the main
role, to clear a permission error. Either collapses the boundary while leaving every piece of
configuration looking correct. `deploy/e2e.sh` probes the live privileges for exactly that reason.

## Bringing your own identity provider

Point `SPIRE_OIDC_AUTH_SERVER_URL` at it and drop the `keycloak` service. The realm must provide:

- **Three confidential clients** — `spire-orchestrator`, `spire-gateway`, `spire-review-worker` —
  each with an **audience mapper**, or login fails with *"No Audience (aud) claim present"*.
- Redirect URIs `<origin>/api/auth/callback`, `<origin>/gw/auth/callback`, `<origin>/wk/auth/callback`.
- **Two realm roles**, `spire-viewer` and `spire-admin`, readable from the **access** token. Reading
  them from the ID token yields an operator with no roles, and every endpoint then denies.

`deploy/keycloak/realm-spire.json` is a working example of all of that.

**The issuer must be reachable under one name from both the browser and the containers.** An
unpinned Keycloak derives its issuer from the `Host` it was called on, so the two would disagree and
every token would fail validation. The bundled instance pins `KC_HOSTNAME` and sets
`KC_HOSTNAME_BACKCHANNEL_DYNAMIC` so front- and backchannel can differ while the issuer stays fixed.

## Verifying a deployment

```bash
set -a; . deploy/.env; set +a
export DEV_VIEWER_PASSWORD=... DEV_OPERATOR_PASSWORD=...
./deploy/e2e.sh http://localhost:34700 http://localhost:34767
```

Twenty-one checks covering what no local dev run can: SCM ingress not being swallowed by the SPA,
role enforcement through the proxy, a WebSocket upgrade traversing nginx, a token minted for one
service being refused by another, and the gateway's live database privileges.

## Images

`ghcr.io/artyomsv/spire-{gateway,orchestrator,review-worker,ui}`. `:edge` tracks `master`; pin a
release tag for anything you intend to keep. Each image carries its FSL-1.1-ALv2 licence as an OCI
label and as a file.
