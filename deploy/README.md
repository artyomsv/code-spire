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
refuse to start unless `SPIRE_TRUSTED_PROXIES` names who may be believed — and a zero-length prefix
(`0.0.0.0/0`, `::/0`) is refused alongside an empty value, since naming everyone is not an answer.

### Why the dashboard is also the proxy

Each service owns one URL prefix and scopes its session cookie to that path, so one service can never
receive another's credential (ADR-022). Cookies scope by host **and** path, which only isolates
anything while all four services answer on **one origin**. The `ui` image is what produces that
origin. Two consequences worth knowing before changing anything:

- **The dashboard must sit at the origin root.** Every redirect target in the services is `/` and the
  cookie paths are absolute. There is no sub-path deployment.
- **`/webhooks` must route to the gateway.** Without that route an SCM delivery reaches the SPA
  fallback instead, every delivery fails, and no review ever starts.
- **The Host header decides where login goes.** The services derive their OIDC `redirect_uri` from
  it, and the proxy answers to any Host it is sent, so a request carrying a forged one produces a
  login redirect pointing at the forger's origin. Set **`SPIRE_PUBLIC_HOST`** to the `host[:port]`
  operators actually use (`spire.example.com`, `localhost:34700`) and the proxy forwards that instead
  of what arrived. It is optional and empty by default — nothing shipped here can know the name you
  will use — and setting it breaks nothing that reaches the stack under another name: those requests
  are served, with the pinned host forwarded, rather than rejected. In Kubernetes it is the chart's
  `publicHost` value, normally the same as `ingress.host`.

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
- Redirect URIs `<origin>/api/auth/callback`, `<origin>/gw/auth/callback`, `<origin>/wk/auth/callback`
  — **exact hosts, never a wildcard.** That list is load-bearing, not paperwork: `redirect_uri` is
  built from the request's Host header, so an entry like `https://*.example.com/*` accepts one built
  from a forged Host and hands the authorization code — an operator session — to whoever forged it.
  Pin `SPIRE_PUBLIC_HOST` as well and the forged Host never reaches the service in the first place.
- **Two realm roles**, `spire-viewer` and `spire-admin`, readable from the **access** token. Reading
  them from the ID token yields an operator with no roles, and every endpoint then denies.

`deploy/keycloak/realm-spire.json` is a working example of all of that, for development only: it has
users, client secrets, the password grant and `localhost` URIs. For a real identity provider, start
from `deploy/keycloak/realm-spire-production.json` (see "Install on any cluster", step 4).

**The issuer must be reachable under one name from both the browser and the containers.** An
unpinned Keycloak derives its issuer from the `Host` it was called on, so the two would disagree and
every token would fail validation. The bundled instance pins `KC_HOSTNAME` and sets
`KC_HOSTNAME_BACKCHANNEL_DYNAMIC` so front- and backchannel can differ while the issuer stays fixed.

## Install on any cluster

The Helm chart with the `production` preset, Postgres you run (or CloudNativePG), the chart's bundled
Redpanda, and an identity provider you already run. This installs the **reviewer only**: gateway,
orchestrator, review worker and dashboard. Agent work (harness reviews, `/fix`, the factory) needs the
run worker, which comes to Kubernetes with M5 (below).

**Not yet run on a real cluster.** The chart renders and its invariants are checked in CI, but no
install with external Postgres and an external identity provider has been done yet
(`docs/UNVERIFIED.md`). Do the steps in this order.

1. **Namespace.** `kubectl create namespace spire`. Everything below goes in it: a pod reads a Secret
   only from its own namespace.

2. **Postgres.** Version 15 or later (the migrations use `NULLS NOT DISTINCT`). Pick one:
   - **Bundled**: the `simple` preset's single Postgres. For evaluation only (no backup).
   - **External**: set `postgres.host`, make the gateway role and schema with the two statements in
     "The gateway's database role" above, and set `postgres.provisionGatewayRole: false` if the chart
     must not make them.
   - **CloudNativePG** (operator 1.26 or later): first make the gateway role's basic-auth Secret, then
     apply the example:

     ```bash
     kubectl -n spire create secret generic spire-db-gateway --type=kubernetes.io/basic-auth \
       --from-literal=username=gateway --from-literal=password="$(openssl rand -hex 24)"
     kubectl -n spire apply -f deploy/examples/cloudnative-pg/cluster.yaml
     ```

     CloudNativePG makes the owner `spire` and its Secret `spire-db-app`; a `Database` object makes the
     `gateway` schema, owned by `gateway`. The chart reads both passwords from those two Secrets
     (`postgres.passwordSecret`, `postgres.gatewayPasswordSecret`), so each password is stored once.
     Connect to the `-rw` Service, never through a Pooler in transaction mode: the orchestrator holds
     a `LISTEN` session.

3. **The two Secrets.** The chart takes Secret names only ("The four secrets" above says why).

   | Secret | Keys |
   |---|---|
   | `secrets.existingSecret` | `SPIRE_ENCRYPTION_KEYSET`, `SPIRE_OIDC_ORCHESTRATOR_SECRET`, `SPIRE_OIDC_GATEWAY_SECRET`, `SPIRE_OIDC_WORKER_SECRET`, and `POSTGRES_PASSWORD` unless `postgres.passwordSecret` is set |
   | `secrets.gatewayExistingSecret` | `SPIRE_ENCRYPTION_WEBHOOK_KEYSET`, and `GATEWAY_POSTGRES_PASSWORD` unless `postgres.gatewayPasswordSecret` is set |

   They must be two different Secrets. The three OIDC secrets come from step 4, so make the main Secret
   after it, or update it then.

4. **The realm.** Copy `deploy/keycloak/realm-spire-production.json` and replace the two
   placeholders: `SPIRE_REALM` with the realm name, `SPIRE_HOST` with the host the browser uses (no
   scheme, no path). Create a **new** realm from the file (a partial import into an existing realm is
   a different path). Then, for each of the three clients, open its Credentials tab, press
   **Regenerate**, and copy the secret into the main Secret's matching key. Whether Keycloak makes a
   secret on import when the file has none is not proven, so do not skip Regenerate. Give your
   operators the realm roles `spire-viewer` and `spire-admin`.

5. **Install.** Copy `deploy/examples/cloudnative-pg/values.yaml`, replace each placeholder
   (`example.invalid`, the pod CIDR in `trustedProxies`, the database host), then:

   ```bash
   helm install spire deploy/helm/spire -n spire \
     -f deploy/helm/spire/values-production.yaml -f my-values.yaml
   ```

   With an empty `image.tag` the chart pulls its own `appVersion`: a chart from a release pulls that
   release's images; this repository's chart pulls `edge`. For settings the chart does not model,
   each service takes `extraEnv` and `extraEnvFrom`. The render refuses a name the chart already sets,
   and on the gateway the master keyset or any reference to the main Secret. It cannot see a
   different Secret that also holds the master keyset: keep that key out of anything the gateway
   loads.

6. **Point the TLS terminator at the dashboard.** The `spire-ui` Service is `ClusterIP`. A terminator
   in the cluster forwards to `spire-ui.spire.svc.cluster.local:8080`; for one outside it,
   `ui.service.type` makes the Service a `NodePort` or `LoadBalancer` (`docs/TLS.md`, "Reaching the
   dashboard from outside the cluster").

7. **Check it worked.** `deploy/e2e.sh` cannot check this install: it signs in with the password grant
   and the dev realm's users, assumes the realm name `spire`, and runs `docker compose exec`. Check
   these by hand:
   - every pod is Ready: `kubectl -n spire get pods -l app.kubernetes.io/name=spire`;
   - each service answers `/q/health/ready` with `200`, for example
     `kubectl -n spire port-forward svc/spire-orchestrator 8080:8080` then
     `curl -fsS http://localhost:8080/q/health/ready` (the same for `spire-gateway` and `spire-worker`);
   - `https://<host>` signs you in through the identity provider and the dashboard shows your roles;
   - the gateway answers a forge's webhook ping (`https://<host>/webhooks/...`);
   - one review on a test pull request.

**No NetworkPolicy is shipped.** The services believe forwarded headers from `trustedProxies`, the pod
network, so on a shared cluster any pod can reach a service port and forge its apparent client
address. Add a NetworkPolicy that lets only the `spire-ui` pod reach the three services if other
workloads share the cluster (`techdebt/global/3-2-no-networkpolicy-isolates-the-services.md`).

## The factory, and why it is not on by default

The run worker — the service that executes agent runs — is behind a compose **profile**, so
`docker compose up` does not start it:

```bash
# From GHCR: the run worker and every image its units run are published (see Images below).
docker compose -f deploy/compose.ghcr.yml --env-file deploy/.env --profile factory up -d

# Or built here: compose.yml builds the run worker, but the unit images are built by hand first.
./deploy/agent/build-codex.sh          # builds, then bakes in the model catalogue
./gradlew :spire-publisher:installDist && docker build -t spire-publisher:latest spire-publisher
./gradlew :spire-model-proxy:installDist && docker build -t spire-model-proxy:latest spire-model-proxy
./deploy/egress/build.sh
docker compose -f deploy/compose.yml --env-file deploy/.env --profile factory up -d
```

**Read this before you do.** The run worker mounts the host Docker socket so it can place run
units, and a Docker socket is **root-equivalent on the host** — `docs/SECURITY.md` says so under
"What is NOT mitigated". The run worker is also the one service that executes untrusted model
output. Those two facts together are why this is a profile and not a default: a compromised run
worker is a compromised machine, and that should be something an operator opted into rather than
something that happened because they typed `up`.

`DockerSocketMountsAreOptInTest` (in `spire-arch`) enforces it: any compose service mounting the
socket must carry a profile. Delete the one line and the build fails rather than the next
`compose up` quietly mounting your socket.

The cheapest mitigation is a daemon that is not this host — point `SPIRE_RUN_DOCKER_HOST` at a
remote or rootless one and drop the socket mount.

### Kubernetes does NOT get the run worker yet, and this is deliberate

The Helm chart, the kustomize overlays and the rendered manifests under `k8s/` carry the three
reviewer services and the dashboard. They do **not** carry the run worker, and adding it would be
wrong rather than merely incomplete.

There is one runtime implementation — `spire-runtime-docker` — and `WorkerRuntimes` says so in as
many words: *"M0 has one arm. Selecting between them by configuration is M5's job, and doing it
now would be a switch with one case in it."* So a Kubernetes deployment of the run worker would
have to mount the **node's** Docker socket into a pod, which is precisely what `SECURITY.md` says
the Kubernetes arm exists to remove:

> Docker socket access is root-equivalent on the host. The run worker drives the daemon directly,
> so a compromised worker is a compromised host. **The Kubernetes arm removes this**; the Docker
> arm cannot.

Shipping a chart template that mounts it would ship the exact thing that sentence promises the
Kubernetes arm does not do. The run worker reaches Kubernetes when a Kubernetes `RunRuntime`
exists — a producer change in `WorkerRuntimes`, planned for M5 — and not before.

## Verifying a deployment

```bash
set -a; . deploy/.env; set +a
export DEV_VIEWER_PASSWORD=... DEV_OPERATOR_PASSWORD=...
./deploy/e2e.sh http://localhost:34700 http://localhost:34767
```

The same checks run over `https` through a test TLS terminator (`deploy/compose.tls.yml`, Caddy with its own
test root; the nightly e2e job does both):

```bash
docker compose -f deploy/compose.ghcr.yml -f deploy/compose.tls.yml --env-file deploy/.env up -d
docker compose -f deploy/compose.ghcr.yml -f deploy/compose.tls.yml exec -T tls \
    cat /data/caddy/pki/authorities/local/root.crt > /tmp/spire-tls-root.crt
SPIRE_E2E_CA_FILE=/tmp/spire-tls-root.crt ./deploy/e2e.sh https://localhost:34743 http://localhost:34767
```

Twenty-eight checks covering what no local dev run can: SCM ingress not being swallowed by the SPA,
role enforcement through the proxy, a WebSocket upgrade traversing nginx, a token minted for one
service being refused by another, and the gateway's live database privileges.

## Images

`:edge` tracks `master`, and every master build also publishes `:sha-<short>` (both amd64 only). A `v*`
tag publishes every image below as `:<version>` (the tag without its `v`) and `:latest`, for amd64 and arm64,
cosign-signed by digest with an SBOM and provenance (`release.yml`). For anything you intend to keep, pin
`SPIRE_VERSION` to a release version, or to a `sha-<short>` between releases. Each image carries its
licence as an OCI label and as a file (LICENSING.md, "Published images").

To check a release image's signature (keyless, bound to the release workflow's identity). Use **cosign 3 or later**:
the release signs with cosign 3, which stores the signature as a new-format bundle, and cosign 2 answers "no
signatures found" for a correctly signed image. Measured on `v0.1.0` (2026-11-15): cosign 3.1.4 verifies
`spire-run-worker:0.1.0`, cosign 2.4.1 does not find the signature.

```bash
cosign verify ghcr.io/artyomsv/spire-run-worker:<version> \
  --certificate-identity-regexp '^https://github.com/artyomsv/code-spire/.github/workflows/release.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

| Image | What it is | Licence | Visibility |
|---|---|---|---|
| `ghcr.io/artyomsv/spire-{gateway,orchestrator,review-worker,run-worker,ui}` | The services and the dashboard | FSL-1.1-ALv2 | public |
| `ghcr.io/artyomsv/spire-publisher` | Run unit: clones, gates and pushes | FSL-1.1-ALv2 | public |
| `ghcr.io/artyomsv/spire-model-proxy` | Run unit: holds the model credential | FSL-1.1-ALv2 | public |
| `ghcr.io/artyomsv/spire-egress-proxy` | Run unit: the agent's only way out | Apache-2.0 | public |
| `ghcr.io/artyomsv/spire-agent-codex` | Reference Codex agent, model catalogue baked in | Apache-2.0 (Codex CLI: Apache-2.0) | public |
| `ghcr.io/artyomsv/spire-agent-claude-code` | Reference Claude Code agent, reviews only | Apache-2.0 recipe; the CLI is Anthropic's | public |

The Claude Code CLI is "All rights reserved", under Anthropic's Commercial Terms, which say nothing
about redistribution. The image is published publicly by operator decision (2026-11-15) and removed on
Anthropic's request. You can also build your own with `./deploy/agent/build-claude-code.sh` and push it
to a registry only you can read; set `SPIRE_FACTORY_AGENT_IMAGE_CLAUDE_CODE` to it and the three
`SPIRE_RUN_REGISTRY_*` values so the run worker can pull it (`deploy/.env.example`).

### Upgrading the run unit's images

`compose.ghcr.yml` names the unit images at `SPIRE_VERSION`, the run worker's own tag. **The run
worker pulls a unit image only when its Docker daemon does not hold it**, so a moving tag is pulled
once and never refreshed: after an upgrade on `edge`, units keep running the first `edge` that daemon
ever pulled, beside a newer run worker. Either pin `SPIRE_VERSION` to a release version or a `sha-<short>`
tag (a new tag is a new image, so it is pulled), or, on `edge`, pull the unit images on the run worker's
daemon at every upgrade:

```bash
for image in spire-publisher spire-model-proxy spire-egress-proxy spire-agent-codex; do
  docker pull "ghcr.io/artyomsv/$image:edge"
done
```

### Operator steps after merge

docker.yml pushes the factory images for the first time on the first master build after the PR that
added them. No manual visibility step remains: each of the six images (`spire-run-worker`,
`spire-publisher`, `spire-model-proxy`, `spire-egress-proxy`, `spire-agent-codex`,
`spire-agent-claude-code`) carries the `org.opencontainers.image.source` label, so GHCR links it to this
public repository, the workflow's token may create it, and it is created **public**. Once the build is green,
pull each anonymously to check.

`spire-agent-claude-code` is public by operator decision (2026-11-15). If Anthropic asks for its removal,
delete the package in GHCR and drop it from the `agents` matrix in `docker.yml`.
