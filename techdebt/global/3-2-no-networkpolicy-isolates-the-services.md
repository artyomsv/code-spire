# No NetworkPolicy keeps other pods away from the service ports

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `deploy/helm/spire/templates/` (no NetworkPolicy template); `trustedProxies` in `deploy/helm/spire/values.yaml` |
| Found during | Review of the any-cluster design (docs/superpowers/specs/2026-11-15-k8s-any-cluster-design.md) |
| Date | 2026-11-15 |

## Issue

The services believe `X-Forwarded-For` and `-Proto` from `trustedProxies`, which on Kubernetes is the pod
network. The chart keeps every service Service `ClusterIP` so that only the dashboard pod is meant to call
them, but nothing in the cluster enforces that: any pod in any namespace can reach
`spire-orchestrator:8080`, `spire-gateway:8080` or `spire-worker:8080` directly, and its forwarded
headers are believed because it is inside the trusted range.

On a cluster that runs only Code Spire this changes little. "Any cluster" includes shared clusters,
where another team's pod, or a compromised one, can forge its apparent client address and the scheme
the services build redirect URIs from.

This is existing behaviour, not caused by the any-cluster work. deploy/README.md says it in one sentence.

## Risks

- A forged `X-Forwarded-For` defeats anything that keys on the client address (logs, rate limits).
- A forged `X-Forwarded-Proto` or `Host` reaches the OIDC redirect URI the services build, where the
  realm's exact redirect list is the only remaining guard.

## Suggested Solutions

1. **A NetworkPolicy template, on by default:** ingress to the three services only from pods labelled
   `app.kubernetes.io/component: ui` of the same release, and to the dashboard from anywhere. A policy
   takes effect only where the cluster's network plugin enforces it, and is silently ignored elsewhere,
   so the README must say how to check that it applies.
2. **Narrow `trustedProxies`** to the dashboard pod's address. Not possible with a CIDR value and a pod
   whose address changes on every restart, so 1 is the real fix.
3. Add a render.sh assertion that the policy exists and names the three services, with a self-test.
