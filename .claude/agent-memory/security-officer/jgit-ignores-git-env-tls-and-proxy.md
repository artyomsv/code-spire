---
name: jgit-ignores-git-env-tls-and-proxy
description: The init clone and the publisher are JGit inside a JVM, so GIT_SSL_CAINFO / SSL_CERT_FILE / NODE_EXTRA_CA_CERTS and HTTPS_PROXY env do nothing there; probe recipe and the temurin USE_SYSTEM_CA_CERTS hook
metadata:
  type: project
---

In this repo the run unit's init container (`spire-clone` → `CloneMain`) and the publisher sidecar
(`spire-publish` → `PublisherMain`) are **JGit 7.3 in a JVM** (`spire-workspace` `api("org.eclipse.jgit…")`),
not the git binary. A review that sees env-based TLS/proxy plumbing (`GIT_SSL_CAINFO`, `SSL_CERT_FILE`,
`HTTPS_PROXY`) reaching "every container" must check those two: the JVM reads none of them, and JGit's
default `JDKHttpConnectionFactory` uses the JDK `cacerts` + `ProxySelector.getDefault()` (system
properties only). Found on the FR-F14 round (2026-09-03): the feature was inert for 2 of 3 containers.

**Why:** the agent image is Node (Codex) and the doc/tests talked about "git", so the natural reading is
that all three containers shell out to git. Nothing in the tests exercises real TLS; the IT only reads the
bundle file back.

**How to apply:** probe rather than assume —
`unzip -p <jgit.jar> 'org/eclipse/jgit/transport/*.class' 'org/eclipse/jgit/util/*.class' | grep -a -c GIT_SSL_CAINFO`
(0 hits on 7.3.0). The jar is in `~/.gradle/caches/modules-2/files-2.1/org.eclipse.jgit/org.eclipse.jgit/`.
`eclipse-temurin:25-jre-alpine` (the publisher base) keeps its `/__cacert_entrypoint.sh` ENTRYPOINT: with
`USE_SYSTEM_CA_CERTS=1` it imports `/certificates/*crt` into a writable truststore and sets
`JAVA_TOOL_OPTIONS` when non-root, and it splits multi-cert PEM files itself — a possible low-code fix,
but a code-level `SSLContext`/`ProxySelector`/`Authenticator` in the publisher is the robust one (JDK also
disables Basic for HTTPS tunnelling by default: `jdk.http.auth.tunneling.disabledSchemes`).

Related: [[docker-java-nameparser-probe]]
