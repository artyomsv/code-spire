# Running the factory behind a corporate proxy (FR-F14)

A run unit is three containers on the worker host: an init clone, the agent, and the publisher. In a
corporate network all three need the same two things — a trust store that accepts your TLS-inspecting
proxy, and the proxy variables themselves — and the agent and publisher images may need a credential
to be pulled at all.

**None of it belongs in an image.** You build your own toolchain image FROM the reference agent
image, so anything baked in there follows your image into every deployment that pulls it: a proxy
address that is wrong in the next environment, a trust store that replaces the correct one, or a
registry password sitting in a layer that `docker image history` prints. The build enforces this —
`NoCorporateEnvironmentIsBakedIntoAnImageTest` fails on the commit that adds one.

Everything below is set on the **run worker**, read once at startup, and applied to every container
of every unit.

## The CA bundle

```bash
SPIRE_RUN_CA_BUNDLE_PATH=/etc/ssl/certs/acme-full-bundle.crt
```

A path on the **worker host**. It is mounted **read-only** into all three containers at
`/etc/spire/ca-bundle.crt`, and three variables are set to point at it:

| Variable | Read by | Behaviour |
|---|---|---|
| `SSL_CERT_FILE` | OpenSSL, so curl and most CLI tooling in your agent image | **Replaces** the trust store |
| `GIT_SSL_CAINFO` | the `git` binary, if your agent image uses one | **Replaces** the trust store |
| `NODE_EXTRA_CA_CERTS` | Node, which is what the Codex arm runs on | **Appends** |

**The init clone and the publisher read none of those, and are handled separately.** They are not a
shell with `git` in it — they are a JVM running JGit, which uses the JDK's own trust store and knows
none of those variable names. So the publisher reads `SSL_CERT_FILE` itself at startup, builds a
trust store from the PEM, and installs a `ProxySelector` from the proxy variables. You configure one
path and one proxy; the difference is handled for you.

This is worth stating because it was wrong once: the first version of this feature set the three
variables and stopped, so the bundle was mounted into all three containers and honoured by one. The
clone failed at the forge and the push failed at the forge, while this page said otherwise.

Two consequences worth knowing before you set it.

**It must be a complete bundle.** Your corporate root *appended to* the public roots, not the
corporate root on its own. Two of the three variables replace the trust store rather than adding to
it, so a corporate-only file makes your internal forge work perfectly and every public TLS call fail
— which looks like an outage at your model provider, not like a configuration mistake. On a Debian
base:

```bash
cat /etc/ssl/certs/ca-certificates.crt /path/to/acme-root.crt > /etc/ssl/certs/acme-full-bundle.crt
```

**Setting one of the three is not enough.** They are read by different stacks. Setting only
`SSL_CERT_FILE` gives you a clone that works and an agent whose first model call fails, because Node
reads neither of the other two. The worker sets all three from the one path, so this is only a
problem if you set them yourself in the image — which is what the build check forbids.

**The mount point is deliberately not `/etc/ssl/certs/ca-certificates.crt`.** Mounting over the
image's own store would make your file the container's entire trust set, which is the failure above
with no way to opt out of it.

**A wrong path is a startup refusal, not a failed run.** The worker will not start if
`SPIRE_RUN_CA_BUNDLE_PATH` does not name a readable file, if the path is **relative**, or if the file
contains a **private key**. Each is a real trap. A missing bind source is not an error in every
container runtime — it can be created as an empty *directory* — so a typo would otherwise produce
three variables pointing at a directory and a TLS failure naming neither. A relative path is worse:
it resolves against the worker and passes the file check, then reaches the runtime as a **volume**
**name**, silently mounting an empty volume at the bundle path. And a combined `server.pem` — the
private key followed by its chain, which is what many corporate tools export — would be mounted into
the container that runs untrusted model output.

**The path is resolved by the container runtime, not by the worker.** Those are the same filesystem
while the worker runs directly on the host, which is how it runs today. If you later run the worker
in a container, the bundle must exist at this same path on the machine the runtime runs on *and* be
mounted into the worker, so its startup check asks about the file the runtime will actually bind.

## The proxy

```bash
SPIRE_RUN_HTTP_PROXY=http://proxy.acme.example:3128
SPIRE_RUN_HTTPS_PROXY=http://proxy.acme.example:3128
SPIRE_RUN_NO_PROXY=forge.acme.example,registry.acme.example,localhost,127.0.0.1
```

Give each URL once; both the upper- and lower-case spellings are set for you. That is not
belt-and-braces — tools genuinely disagree (git reads the lower case, most JVM and Go tooling the
upper), and setting one spelling produces a unit where some calls are proxied and some are not, which
presents as an intermittent network fault rather than as configuration.

**Set `NO_PROXY` whenever you set a proxy.** An internal forge or a self-hosted model endpoint is
routinely reachable only directly, and a proxy with no exception list turns the clone into a hang
that ends at the init timeout with nothing useful in the log.

A proxy URL may carry basic auth (`http://user:pass@proxy:3128`). That is supported, and it is why
these values are redacted wherever the environment is printed.

## A private registry

```bash
SPIRE_RUN_REGISTRY_HOST=registry.acme.example
SPIRE_RUN_REGISTRY_USERNAME=spire-factory
SPIRE_RUN_REGISTRY_SECRET=...
```

**All three together or none.** A host and username with no secret would fall back to an anonymous
pull, so a private image comes back as *not found* and you go and check the image reference rather
than the password. The worker refuses to start on a partial credential instead.

The host must match the image reference. A credential for `registry.acme.example` is offered to
`registry.acme.example/team/agent:1` and to nothing else — your corporate password is never presented
to Docker Hub because someone referenced `alpine:3.20`. A **port is part of the host**, so a registry
at `registry.acme.example:5000` must be configured with the port. Docker Hub may be written
`docker.io`, `index.docker.io` or `registry-1.docker.io`; all three are recognised as one registry, so
a fully qualified Hub reference matches a Hub credential.

**This credential never reaches a container.** It is handed to the runtime and used on the image pull
alone, so `docker inspect` on a run unit does not print it and the agent — which runs untrusted model
output at full shell access — cannot read it from its own environment. You can check this directly:

```bash
docker inspect --format '{{json .Config.Env}}' <a run container>
```

## What the agent can read

The registry credential is invisible to a container. **The proxy credential is not, and cannot be.**
Every container must route through the proxy, so the proxy URL — basic auth included — is in every
container's environment, and the agent runs untrusted model output at full shell access and can read
its own environment.

That is a deliberate trade, not an oversight. Give the proxy a service account scoped to proxying and
not reused elsewhere. What Code Spire *can* do, and does, is keep that password out of anything it
stores: the run transcript and `factory_run.failure_detail` are scrubbed of it in every form it takes
— literal, URL-encoded, and the `Proxy-Authorization: Basic` header a verbose `curl` prints. A
password too short for that scrub to act on safely is refused at startup rather than silently left in
the clear.

## What a working corporate deployment looks like

```bash
# on the worker host
SPIRE_RUN_CA_BUNDLE_PATH=/etc/ssl/certs/acme-full-bundle.crt
SPIRE_RUN_HTTP_PROXY=http://proxy.acme.example:3128
SPIRE_RUN_HTTPS_PROXY=http://proxy.acme.example:3128
SPIRE_RUN_NO_PROXY=forge.acme.example,registry.acme.example,localhost,127.0.0.1
SPIRE_RUN_REGISTRY_HOST=registry.acme.example
SPIRE_RUN_REGISTRY_USERNAME=spire-factory
SPIRE_RUN_REGISTRY_SECRET=...
```

## When something is wrong

| Symptom | Cause |
|---|---|
| Worker refuses to start naming `ca-bundle-path` | The path is not a readable file on the worker host |
| Worker refuses to start naming a missing registry part | One or two of the three registry values are set |
| The init clone fails with a certificate error | The bundle is missing, or is not a complete bundle |
| The clone works and the agent's first model call fails | Corporate-only bundle: the public roots were not appended |
| The clone hangs until the init timeout | A proxy is set and the forge is not in `NO_PROXY` |
| The agent image pull reports *not found* | The registry host does not match the image reference |
