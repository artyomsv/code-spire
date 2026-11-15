# Kubernetes on any cluster: reviewer-only install with external Postgres and identity provider

Status: design, 2026-11-15. Part of #391.

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
- Postgres runs on CloudNativePG (17 clusters, storage class `longhorn-cnpg`). A CloudNativePG application
  owner cannot `CREATE ROLE`, so the chart's gateway-role job cannot run against it.
- No Kafka in the cluster. The chart's bundled Redpanda is enough while every Kafka client is in-cluster.
- The identity provider is public; pods and browsers reach it under one name.

## Goals

1. `helm install` with `values-production.yaml` plus bundled Kafka works against CloudNativePG Postgres and an
   external identity provider, with no hand patch.
2. A cluster with no ingress controller can expose the dashboard through chart values, not `kubectl patch`.
3. A per-service escape hatch for settings the chart does not model.
4. A production realm template: the three clients, roles and mappers, with placeholders and no users or secrets.
5. The README says, in order, what to create before `helm install`.

## Non-goals

- The run worker, factory values, harness reviews (M5).
- Kafka outside the cluster, Kafka SASL or TLS (after M5, when a client outside the cluster exists again).
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

The defaults render exactly today's Service. `loadBalancerSourceRanges` renders only for `LoadBalancer`.
TLS.md "Reaching the dashboard from outside the cluster" is rewritten: values, not a patch; and an
in-cluster terminator forwarding to the ClusterIP Service stays the first recommendation, because a
NodePort or LoadBalancer re-opens requirement 3 (a plaintext port beside the terminator).

### 2. `extraEnv` and `extraEnvFrom`

Per service: `orchestrator`, `gateway`, `worker`, `ui`. Each takes a list of Kubernetes `EnvVar` /
`EnvFromSource`, appended after the chart's own entries. Empty by default. The README warns that `extraEnvFrom`
on the gateway must never name the main Secret: the master keyset must not reach the internet-facing edge.
`render.sh` keeps asserting that for the presets; it cannot police an operator's own values, and says so.

### 3. CloudNativePG example

`deploy/examples/cloudnative-pg/` with:

- `cluster.yaml`: a `Cluster` whose application database is `spire`, owner `spire`, a managed role `gateway`
  (login, password from a Secret), and `postInitApplicationSQL` that creates the `gateway` schema owned by
  `gateway`. Exactly the two statements in README "gateway role", never a broader grant.
- `values.yaml`: chart values for it: `postgres.bundled: false`, `host: <cluster>-rw.<ns>.svc.cluster.local`,
  `provisionGatewayRole: false`, `kafka.bundled: true`, `oidc.bundled: false`, `ingress.enabled: false`.

How the role and schema are created in CloudNativePG (`postInitApplicationSQL` ordering against managed roles)
is the builder's to settle, and it must be proven on a real CloudNativePG cluster before this example is called
working: the home install is that proof. Until then the README marks it "not yet run".

### 4. Production realm template

`deploy/keycloak/realm-spire-production.json`: derived from `realm-spire.json`. Three confidential clients with
audience mappers, the two realm roles on the access token, redirect URIs `https://SPIRE_HOST/{api,gw,wk}/auth/callback`
as literal placeholders, no users, no client secrets (Keycloak generates them on import). A test asserts the
template has no `users`, no `secret` values and no wildcard redirect URI, and that it names the same clients
and roles as the dev realm.

### 5. README: "Install on any cluster"

One ordered section in `deploy/README.md`: create the namespace; Postgres (bundled, external, or CloudNativePG
with the example); the two Secrets and their keys; the realm (template + four placeholders to replace);
`helm install` with the example values; point the terminator at the Service; verify with `deploy/e2e.sh`.
The "Bringing your own identity provider" section links the template.

### Tests (CI only)

- `render.sh`: default render has `type: ClusterIP` and no `loadBalancerSourceRanges`; a render with
  `ui.service.type=LoadBalancer` and ranges has both; `extraEnv` on each service appears in that service only;
  the example values render.
- `render-manifests.sh --check`: the shipped rendered manifests do not change (defaults unchanged).
- The realm template test above.

### Docs

UNVERIFIED.md: the chart has never been installed on a real cluster with external Postgres and an external
identity provider; the CloudNativePG example has not run. The home install closes both. HISTORY.md entry.

## After this PR

1. Push tag `v0.1.0`: the first real release (images and the chart with this PR in it).
2. DevOps repository: the `code-spire` realm on the production Keycloak at `auth.spire.stukans.com`
   (it needs its own certificate: `*.stukans.com` does not cover a second-level name) and DNS.
3. DevOps repository: the home install at `v0.1.0`, plain Secrets made by hand, CloudNativePG with backups,
   nginx proxy manager forwarding `spire.stukans.com` to the Service. Live review on `spire-test` as the proof.
4. M5: the Kubernetes runtime, then agent work inside the cluster.
