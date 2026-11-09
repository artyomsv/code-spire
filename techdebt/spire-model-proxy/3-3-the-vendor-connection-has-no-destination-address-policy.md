# The model proxy's vendor connection has no destination-address policy

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | `spire-model-proxy/src/main/java/dev/codespire/modelproxy/EgressProxy.java` (the `ProxySelector` naming the unit's egress proxy), `spire-runtime-docker/src/main/java/dev/codespire/runtime/docker/TinyproxyConfig.java` (the hostname filter) |
| Found during | PR #394 review, finding security/M-3 |
| Date | 2026-11-09 |
| Status | Deferred by the operator |

## Issue

A proxied unit pins its vendor by hostname: the route names `https://api.openai.com` or `https://chatgpt.com`, the
model proxy sends a CONNECT for that name through the unit's egress proxy, and tinyproxy's filter allows the name. No
step checks the address the name resolves to. If an allowed vendor hostname resolved to loopback, link-local (the
cloud metadata address among them), an RFC 1918 range or an internal IPv6 range (`::1`, `fe80::/10`, `fc00::/7`),
tinyproxy would connect there. A check in the model proxy before the CONNECT would not close it: tinyproxy resolves
the name again after any such check, so a rebinding DNS answer passes the check and connects elsewhere.

## Why it is deferred

- **The credential is not disclosed to a wrong address.** The model proxy verifies the vendor's TLS certificate
  against the hostname (the JDK's default, or the deployment's bundle from `SSL_CERT_FILE`). An internal address
  without a certificate for `api.openai.com` or `chatgpt.com` fails the handshake before a header is sent.
- **What remains is a connection, not a leak:** an internal service learns that something connected and sees a TLS
  ClientHello.
- **A real fix changes step 2's design.** Tinyproxy's allowlist is by hostname, chosen so that a vendor's changing
  address set needs no configuration. Enforcing addresses means connecting by a validated, pinned address, or a
  network policy at the egress point, which is a change to the egress layer every unit uses, not to this proxy.

## Risks

- A compromised or misconfigured resolver on the host, or a vendor DNS record changed to an internal address, makes
  the unit connect to an internal address. With a corporate TLS-inspecting proxy whose CA the deployment trusts, that
  proxy is the one place where a wrong destination could also present a trusted certificate.

## Suggested Solutions

- Resolve in one place and connect by the address checked there: the model proxy (or the egress proxy) resolves the
  vendor name, refuses any answer in a loopback, link-local, private, unique-local or otherwise non-public range,
  including mixed answers, and connects to that address with the hostname kept for SNI and certificate checks, so no
  second resolution can rebind it. On the corporate-proxy path the check belongs to the corporate proxy, and the
  deployment documents that.
- Or enforce at the network: an egress rule on the unit's egress network that drops connections to non-public
  ranges (Docker: iptables on the bridge; Kubernetes: a NetworkPolicy with `ipBlock` exceptions), so the policy holds
  whatever resolves where.
- Tests to add with either: an allowed name resolving to each private, link-local, loopback IPv4 and IPv6 range, a
  mixed answer, and a rebinding answer, each proven to reach no forbidden address; a public destination still works.
  Loopback support for the module's own tests must stay behind the test-only plaintext switch.
