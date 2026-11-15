# Kubernetes on any cluster: reviewer-only install with external Postgres and identity provider

Status: design, 2026-11-15, revised after review the same day. Part of #391.

## Why

The operator will run Code Spire on a home k3s cluster, with sign-in on a production Keycloak in another
cluster (DigitalOcean). The deployment itself lives in the operator's own DevOps repository and pulls the
public GHCR images. This repository must give **anyone** what they need to do the same on any cluster: a chart
that installs without hand patches, and documents that say what to create first.

The first install is **reviewer only**: gateway, orchestrator, review worker and dashboard. Agent work (harness
reviews, `/fix`, the factory) needs the run worker, which today needs a Docker daemon. It waits for M5, whose
Kubernetes runtime runs one pod per run inside the cluster (operator decision 2026-11-15). So this design adds
no run worker, no factory values and no Kafka listener outside the cluster.

## What the home cluster taught (measured 2026-11-15)

- No ingress controller. TLS ends at nginx proxy manager, which runs **as a pod** in the cluster
  (`dnsPolicy: ClusterFirst`), so it can forward to `spire-ui.<namespace>.svc.cluster.local:8080` and the
  Service can stay `ClusterIP`.
- Postgres runs on CloudNativePG, operator 1.28.1 (17 clusters, storage class `longhorn-cnpg`). Its `Database`
  object has a `schemas` field. A CloudNativePG application owner cannot `CREATE ROLE`, so the chart's
  gateway-role job cannot run against it.
- No Kafka in the cluster. The chart's bundled Redpanda is enough while every Kafka client is in-cluster.
- The identity provider is public; pods and browsers reach it under one name.

## Goals

1. `helm install` with `values-production.yaml` plus bundled Kafka works against CloudNativePG Postgres and an
   external identity provider, with no hand patch and no password stored twice.
2. A cluster with no ingress controller can expose the dashboard through chart values, not `kubectl patch`.
3. A per-service escape hatch for settings the chart does not model, that cannot break the chart's own
   security invariants.
4. A production realm template: the three reviewer clients, roles and mappers, with placeholders and no users
   or secrets.
5. The README says, in order, what to create before `helm install`, and how to check the result on a real
   cluster.

## Non-goals

- The run worker, factory values, harness reviews (M5). The dev realm's fourth client, `spire-run-worker`, is
  left out of the template on purpose; M5 adds it.
- Kafka outside the cluster, Kafka SASL or TLS (after M5, when a client outside the cluster exists again).
- A NetworkPolicy. Today any pod in the cluster can reach a service port and forge forwarded headers; that is
  existing, written down in one sentence in the README and as a techdebt entry, not fixed here.
- Anything specific to the operator's cluster: host names, realm names, IPs. Those live in the DevOps repository.
- Creating secrets in the chart. The chart keeps taking Secret names only (values.yaml explains why).

## Design

### 1. `ui.service`

```yaml
ui:
  service:
    type: ClusterIP            # ClusterIP | NodePort | LoadBalancer
    annotations: {}            # e.g. a MetalLB address-pool or loadBalancerIPs annotation
    loadBalancerSourceRanges: []
```

The defaults render exactly today's Service. `loadBalancerSourceRanges` renders only for `LoadBalancer`. No
`loadBalancerIP` field (deprecated upstream; use the annotation). TLS.md "Reaching the dashboard from outside the
cluster" is rewritten: values, not a patch; an in-cluster terminator forwarding to the ClusterIP Service stays
the first recommendation, because a NodePort or LoadBalancer re-opens requirement 3. The nginx proxy manager
table gets a row for the in-cluster case: Forward Hostname `spire-ui.<namespace>.svc.cluster.local`, port `8080`.

### 2. `extraEnv` and `extraEnvFrom`, with render-time refusals

Per service: `orchestrator`, `gateway`, `worker`, `ui`. Each takes a list of Kubernetes `EnvVar` /
`EnvFromSource`, appended after the chart's own entries. Empty by default.

The chart **fails the render** (Helm `fail`, naming the value) when:

- an `extraEnv` entry repeats a name the chart already sets on that container (duplicates override chart-owned
  settings such as the gateway's database user, and server-side apply rejects them anyway);
- gateway `extraEnv` has a `secretKeyRef` or gateway `extraEnvFrom` has a `secretRef` naming
  `secrets.existingSecret` (the main Secret, which holds the master keyset);
- gateway `extraEnv` sets `SPIRE_ENCRYPTION_KEYSET` by any means.

`render.sh` greps env names, so it cannot see `envFrom`; these refusals are the guard, and `--self-test` renders
each forbidden shape and requires the render to fail. The README says what remains the operator's own
responsibility (a different Secret that also holds the master keyset).

### 3. Database passwords from any Secret

```yaml
postgres:
  passwordSecret: {name: "", key: ""}          # empty: POSTGRES_PASSWORD from secrets.existingSecret (today)
  gatewayPasswordSecret: {name: "", key: ""}   # empty: GATEWAY_POSTGRES_PASSWORD from gatewayExistingSecret
```

Set, the services read the password from that Secret and key, e.g. the CloudNativePG basic-auth Secret's
`password`. So a password lives once. The gateway password Secret must never be the main Secret's name (render
refusal, like section 2). Defaults render exactly today's manifests.

### 4. Smaller chart values

- `kafka.storageClass` and `postgres.storageClass` (empty: cluster default) for the bundled PVCs.
- `image.tag` empty by default, falling back to the chart's `appVersion`, so the chart packaged at a release
  pulls that release. The repository's rendered manifests and dev presets set `edge` explicitly so they do not
  drift.

### 5. CloudNativePG example

`deploy/examples/cloudnative-pg/`:

- `cluster.yaml`: a `Cluster` (application database `spire`, owner `spire`, Postgres 15 or later: the
  migrations use `NULLS NOT DISTINCT`), a managed role `gateway` (login, password from a basic-auth Secret), and a
  `Database` object for `spire` with `schemas: [{name: gateway, owner: gateway}]`. Not `postInitApplicationSQL`:
  it runs at bootstrap, before managed roles exist, so the schema's owner would be missing. The gateway needs
  nothing more (no grants, extensions or search path in any migration).
- `values.yaml`: `postgres.bundled: false`, `host: <cluster>-rw.<ns>.svc.cluster.local` (the read-write service;
  no transaction-mode pooler, the orchestrator uses `LISTEN`), `provisionGatewayRole: false`, both
  `passwordSecret`s pointing at the CloudNativePG Secrets, `kafka.bundled: true`, `oidc.bundled: false`,
  `ingress.enabled: false`, `publicHost`, `trustedProxies`.

The home install is the first real run of this example. Until then the README marks it "not yet run".

### 6. Production realm template

`deploy/keycloak/realm-spire-production.json`: three confidential clients (`spire-orchestrator`,
`spire-gateway`, `spire-review-worker`) with audience mappers, the realm roles `spire-viewer` and `spire-admin`
on the access token. Four placeholders, literal strings: `SPIRE_REALM`, `SPIRE_HOST` (in redirect URIs
`https://SPIRE_HOST/{api,gw,wk}/auth/callback`, web origins and post-logout URIs). Compared with
`realm-spire.json`, it has: no users; no client secrets; `sslRequired: external`; `directAccessGrantsEnabled:
false`; no `http://` or `localhost` URI or web origin; no wildcard in any redirect or post-logout URI; no
"(dev)" display name; no `spire-run-worker` client.

Whether Keycloak generates a confidential client's secret on import when the JSON has none is **not proven**.
The README says: after import, open each client's Credentials tab and press Regenerate, then copy the secret
into the main Secret.

### 7. README: "Install on any cluster"

One ordered section in `deploy/README.md`: namespace; Postgres (bundled, external, or CloudNativePG with the
example); the two Secrets and their keys; the realm (template, placeholders, Regenerate); `helm install` with
the example values; point the terminator at the Service; check it worked. The "Bringing your own identity
provider" section links the template. NOTES.txt points to the same checks.

`deploy/e2e.sh` cannot check this install (it signs in with the password grant and dev users, fixes realm
`spire` and runs `docker compose exec`). The real-cluster checks are: all pods Ready; `/q/health/ready` of each
service through `kubectl port-forward`; the dashboard at `https://<host>` signs in through the identity provider
and shows the operator's roles; the gateway answers a forge's webhook ping; one review on a test pull request.

## Tests (CI only: `manifests.yml`, and `testFast` for the realm test)

- `render.sh` positives: default render has `type: ClusterIP`, no `loadBalancerSourceRanges`, today's password
  references and `image.tag` resolution as before; a render with `ui.service.type=LoadBalancer` and ranges has
  both; `extraEnv` on each service appears in that service only; the `passwordSecret`s become `secretKeyRef`s to
  the named Secret and key.
- `render.sh` CloudNativePG example: render `deploy/examples/cloudnative-pg/values.yaml` **with no `--set`**
  and assert: no gateway-role Job, no Ingress, a Redpanda StatefulSet, no Postgres StatefulSet, both passwords
  from the CloudNativePG Secrets.
- `render.sh --self-test`: each refusal in sections 2 and 3 renders its forbidden shape and the render fails.
- kubeconform validates `cluster.yaml` (with the CloudNativePG CRD schemas, or skip-missing-schemas stated).
- A realm template test in `spire-arch` (runs in `testFast`; render.sh has no JSON tool) asserting every point
  of section 6's list, and that its clients and roles are the dev realm's minus `spire-run-worker`.
- `render-manifests.sh --check`: the shipped rendered manifests do not change.

## Docs

UNVERIFIED.md: the chart has never been installed on a real cluster with external Postgres and an external
identity provider; the CloudNativePG example has not run; secret generation on realm import is unproven. The
home install closes the first two. HISTORY.md entry. Techdebt entry: no NetworkPolicy.

## After this PR

1. Push tag `v0.1.0`: the first real release (images and the chart with this PR in it).
2. DevOps repository: the `code-spire` realm on the production Keycloak at `auth.spire.stukans.com`
   (it needs its own certificate: `*.stukans.com` does not cover a second-level name) and DNS.
3. DevOps repository: the home install at `v0.1.0`, plain Secrets made by hand, CloudNativePG with backups,
   nginx proxy manager forwarding `spire.stukans.com` to the Service. Live review on `spire-test` as the proof.
4. M5: the Kubernetes runtime, then agent work inside the cluster.
