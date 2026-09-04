---
name: urldecoder-proxy-userinfo-traps
description: java.net.URLDecoder is a FORM decoder, not an RFC 3986 one — it throws on a bare '%' (quoting two secret chars in the message) and turns '+' into space; where the factory parses proxy userinfo, and where publisher stderr actually goes
metadata:
  type: project
---

`java.net.URLDecoder.decode(s, UTF_8)` is `application/x-www-form-urlencoded` decoding, and the
factory uses it on proxy-URL **userinfo** in two places (`EnterpriseEnvironmentConfig.decode` in
`spire-run-worker`, `CorporateTransport.decode` in `spire-publisher`). Measured 2026-09-04 with a
single-file probe (`java Probe.java`, JDK 21 — same on 25):

- `decode("100%")` and `decode("%zz")` **throw** `IllegalArgumentException`, and the message quotes
  the two characters after the `%` (`Error at index 0 in: "zz"`) — a fragment of the password.
- `decode("a+b")` = `a b`; curl and RFC 3986 keep `+` literal in userinfo. Fix is
  `decode(value.replace("+", "%2B"))`.
- `URLEncoder.encode("p/ss x")` = `p%2Fss+x` (uppercase hex, `+` for space) — so re-encoding a
  decoded password never reproduces the operator's own spelling; the raw form must be kept too.

**Why it matters here:** `RunFailures.scrubFor` is called at launch AFTER `runtime.create(unit)` and
again inside `RunDispatcher`'s catch-all, so an exception out of `proxyCredentials()` escapes past
the manual ack, skips `registry.forget`, and leaves a credential-bearing unit the watchdog exempts.
The only thing that used to catch a malformed `%` was the (now deleted) startup call to
`proxyCredentials()` from `requireScrubbableProxyPasswords` — an accidental refusal.

**Publisher stderr:** `System.Logger` with no config → JUL `ConsoleHandler` → stderr, two lines.
The worker's `LogChannel.PUBLISHER` reader hands every line to `PublisherOutcome.accept`, which
skips non-JSON at DEBUG — so publisher stderr does NOT enter `run_event`; it is only in
`docker logs` of the unit. The build file added `slf4j-nop` specifically to keep stderr quiet.

**How to apply:** any review touching proxy-URL parsing, `SecretScrub` construction, or a claim that
"a scrub construction failure degrades" — check it is guarded per credential like the two decrypt
`catch` blocks in `scrubFor`, and that a startup path still evaluates the userinfo.
