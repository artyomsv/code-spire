---
name: docker-java-nameparser-probe
description: How to run a throwaway Java probe against docker-java 3.5.1 from the Gradle cache (classpath needs commons-lang3), and what NameParser.resolveRepositoryName answers for Hub-shaped references
metadata:
  type: reference
---

To measure a docker-java behaviour instead of reading about it, run a single-file Java program with
`java -cp "<core.jar>;<api.jar>;<commons-lang3.jar>" Probe.java` from the scratchpad. All three jars are
under `~/.gradle/caches/modules-2/files-2.1/` (`com.github.docker-java/docker-java-core`,
`…/docker-java-api`, `org.apache.commons/commons-lang3`); without lang3 `NameParser` throws
`NoClassDefFoundError` on first use. Windows classpath separator is `;`.

Measured on 3.5.1 (2026-09-03): `NameParser.resolveRepositoryName(ref).hostname` gives
`https://index.docker.io/v1/` (= `AuthConfig.DEFAULT_SERVER_ADDRESS`) for `alpine:3.20` and `acme/app:1`,
`docker.io` for `docker.io/library/alpine`, THROWS `InvalidRepositoryNameException` for
`index.docker.io/acme/app`, and the first segment verbatim (case preserved, port kept) for anything with a
dot/colon/localhost. `evil.example/registry.acme.example/app` resolves to `evil.example` — the hand-rolled
`DockerRunRuntime.registryHostOf` agrees on every case except the Hub spellings, where it answers
`registry-1.docker.io` for the bare form and `docker.io` for the qualified one.

**How to apply:** any review of registry-credential matching or image-reference parsing in
`spire-runtime-docker`. The same recipe works for `Bind`/`AccessMode` serialisation questions.

Related: [[docker-java-wait-semantics]], [[jgit-ignores-git-env-tls-and-proxy]]
