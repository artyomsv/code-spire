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
| `SSL_CERT_FILE` | OpenSSL, so curl and most CLI tooling | **Replaces** the trust store |
| `GIT_SSL_CAINFO` | git — the init clone and the publisher's push | **Replaces** the trust store |
| `NODE_EXTRA_CA_CERTS` | Node, which is what the Codex arm runs on | **Appends** |

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

**A wrong path is a startup refusal, not a failed run.** If `SPIRE_RUN_CA_BUNDLE_PATH` does not name
a readable file the worker will not start, and says which setting to fix. This is not defensive
tidiness: a missing bind source is not an error in every container runtime — it can be created as an
empty *directory* — so without the check a typo produces three variables pointing at a directory and
a TLS failure that names neither the mount nor the setting.

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
to Docker Hub because someone referenced `alpine:3.20`.

**This credential never reaches a container.** It is handed to the runtime and used on the image pull
alone, so `docker inspect` on a run unit does not print it and the agent — which runs untrusted model
output at full shell access — cannot read it from its own environment. You can check this directly:

```bash
docker inspect --format '{{json .Config.Env}}' <a run container>
```

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
