# Code Review State: global / credential-scrub-forms

Last reviewed: 2026-09-04
Rounds completed: 1

Round 1 over PR #107 (`fix/credential-scrub-forms`), the branch taking the two security findings PR
#106's review dismissed with reasons. Four lenses. Semgrep 6 files, 0 findings. qa independently
reproduced 301 suites / 2597 tests / 0 failures before reporting, and reproduced all three claimed
mutations on a `git archive` copy rather than in the worktree.

**One HIGH, found by three lenses independently, and it is a defect this branch introduced.** Not in
what was added — in what was *deleted*.

`requireScrubbableProxyPasswords` was, unnoticed, **the only startup caller of
`proxyCredentials()`**. Its stated premise was a length rule, and I checked that premise and deleted
it. The call had a second function nobody had named: `URLDecoder.decode` throws on a bare `%`, which
is a legal password character an operator writes, and that throw used to happen at boot. Afterwards
the first caller is `RunFailures.scrubFor`, on the run-launch path — **after** `runtime.create(unit)`
has put the model key and the git write token into three containers, and again inside
`RunDispatcher`'s own catch, where a second throw escapes before the `finally` that calls
`registry.forget`. The dispatcher's comment states the consequence: a credential-bearing sandbox
permanently unreclaimable by the watchdog. One mistyped character, every run, deterministically.

**The lesson, which is the useful part.** Deleting a control because its *stated* reason has become
false is only safe once you know what else the control was doing. The reason was documentation; the
call was behaviour. A grep for callers would have shown it in seconds and I did not run one.

## Resolved (fixed in code; do not re-raise)

- [sec/H1, cr/1, qa/F1] **A bare `%` in a proxy password threw on the run-launch path and leaked the
  unit.** `decode` never throws now, returning the value unchanged when it is not percent-encoded —
  which is also the truthful answer, since such a value already IS its own decoded form and
  `bothSpellings` correctly collapses to one entry. Asserted by
  `aBarePercentInTheProxyPasswordDoesNotThrowOutOfTheScrub` — round 1
- [sec/H1 second half] **`scrubFor`'s proxy call was unguarded** while the two decrypt calls above it
  were, so one bad proxy value disarmed the SCM and harness scrub too — the opposite of the
  per-credential degradation that method documents. Now guarded like its siblings — round 1
- [sec/M1] **The write token was paired with the READ username.** `Credentials.Scm` carries a
  `writeUsername` precisely "so the call sites are already correct when a deployment issues two", and
  the comment ten lines below in the same method states the rule the code broke: a `base64(user:secret)`
  form built with the wrong username appears on no wire. The identical defect this branch fixes for
  the proxy credential, two credentials apart, in one method — round 1
- [sec/L2, cr/6] **`URLDecoder` is a FORM decoder and turns `+` into a space**, which no URI userinfo
  means by it. A password `a+b` yielded a "decoded" form `a b` that appears on no wire while the real
  header form went uncovered. `+` is escaped before decoding now — round 1
- [sec/L3] The registry credential reached no scrub. Defence in depth — nothing places it on a
  container and no daemon pull error echoes it — closed for one entry — round 1
- [qa/F2] **The WARN was asserted by nothing.** It is the entire stated compensation for removing the
  floor, and in a class whose purpose is keeping credentials out of logs, adding `secret` to its
  arguments would have passed all 2597 tests. Now asserted to fire, to carry the length, and to carry
  neither the value nor the username — with a negative control so it is not just noise. Modelled on
  `spire-diff`'s `warningsFrom`, the other framework-free module that had to solve this — round 1
- [qa/F3] **`bothSpellingsOfOneProxyPasswordAreScrubbed` could not see a decode-only regression.** Its
  `%40` fixture is exactly what `URLEncoder` re-encodes `p@ss` back to — no hex letter, so no case to
  differ on — so the decoded credential's derived form reproduced the raw text by coincidence.
  Measured: it survived that mutation. `%2f` removes the coincidence — round 1
- [qa/note] The two new proxy tests would have passed a scrub that redacted *everything*. The host
  must survive, or an operator gets a failure that names nothing — round 1
- [cr/4, rules/4, qa/F2] **"logged once" was never true** — a scrub is built per run launch and again
  per failure. Said plainly rather than fixed by deduplicating, because a reader who believes it is
  deduplicated will build on that — round 1
- [rules/1, sec/M2, qa/F4] **Three documents still described the deleted rule**, two of them
  operator-facing: `.env.example` and `deploy/agent/CORPORATE-ENVIRONMENT.md` both promised a startup
  refusal, and `docs/factory/MODULES.md` documented the floor in full and named a constant that no
  longer exists — round 1
- [cr/3, rules/3, qa/F6] **An orphaned javadoc carrying now-false text**, sitting directly above its
  own correction; plus the stacked pair on `proxyCredentials` that discarded the reasoning for two
  already-fixed defects. Fourth and fifth instances this session — round 1
- [cr/10, qa/F5] The `assertThrows` import left by the deleted refusal test — round 1
- [cr/9, qa/F6] The tombstone comment and double blank line left by the deletion — round 1
- [cr/8] The order assertion's stated reason was not a reason — `SecretScrub` sorts longest-first, so
  list order changes no behaviour. Kept as an exact-list assertion, which IS worth pinning, with an
  honest reason — round 1
- [cr/5, qa/F7] **`SecretScrubHasOneHomeTest` did not exist**, while `SecretScrub`'s javadoc cited the
  guard as precedent and `OutcomeWriterTest` rested on "structural, not an assertion". Worse, the
  moment the two implementations stopped differing was the moment a re-added local copy stopped
  failing any test. Written, allowlist empty, mutation-verified — round 1
- [teammate sweep] `RunEventStreamTest`'s "long enough to clear the scrub's floor" javadoc, and the
  M1 task-1 record's `[sec-L2]`, which had named **all three** of the read-username, floor and
  form-encoding defects as one Open entry. It sat there while two later rounds found them one at a
  time. **An Open entry naming several defects is not a single item** — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)

- [cr/12] **Derive the decoded form inside `SecretScrub` rather than in `bothSpellings`.** The
  argument is good — that class already derives the URL-*encoded* form, so deriving the decoded one
  is symmetric, would delete `bothSpellings`, and would cover a token pasted percent-encoded into the
  registry. Not taken because it changes the shared class's contract for every caller to serve one,
  and the reviewer marks it "not required for this change; worth recording if a third caller
  appears". Recorded here rather than as debt, because it is a design option and not a gap.
- [cr/1 third part, sec/H1 remediation] **Call `proxyCredentials()` at startup on purpose.** Proposed
  alongside the lenient `decode`. With `decode` unable to throw there is nothing left for a startup
  call to catch, so it would be a no-op that reads like a guard — the shape this session has already
  removed twice.
- [sec/L4] The inference channel inherent to scrubbing a short secret: where the marker appears in
  predictable text can narrow the value. Not fixable in a scrubber — it is a property of a weak
  credential — and the WARN already points at the remedy.
- [cr/11, sec/L1] Make `SecretScrub` silent and let callers report the condition with the credential's
  role. Genuinely better — the current line cannot say *which* of SCM, harness or proxy is short. Not
  taken here because it changes the class's shape to improve a log line, and the security-relevant
  half (it fires, and carries neither value nor username) is now asserted.
