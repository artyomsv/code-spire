# Code Review State: global / m1-task2-run-event-stream

Last reviewed: 2026-09-02
Rounds completed: 1

## Resolved (fixed in code; do not re-raise)
- [cr-C1 / qa-C1 / sec-M1] The socket route could never match a real run id. A WebSocket path parameter compiles to a Vert.x one-segment match and every run id contains a slash, so the handshake 404'd for every run that exists — while the javadoc claimed the parameter "is matched greedily". The run id rides the query string now, which a path template cannot hold. Proven at runtime by qa: a slashless id connected, a real one was refused — round 1
- [cr-C2 / qa-C2 / sec-M1] `push` looked connections up by simple class name where the framework defaults the endpoint id to the fully qualified name, so it matched nothing and every push was a no-op — a tail that opened, showed its snapshot, and never moved. The endpoint id is declared and named once, used from both sides — round 1
- [sec-H1] The transcript carried the model API key to a viewer. The agent runs at full access and the harness relays tool output verbatim, so `printenv` in a tool call put the key in a `TOOL_RESULT`; `EXECUTION-LAYER.md` requires credentials redacted from every event and transcript before leaving the worker, and only failure details were. The scrub runs inside `publish`, so no future kind can reach the sink unredacted — round 1
- [sec-H2 / cr-C3 / qa-C3] The drop-and-log path did not exist. The base deserializer throws rather than answering null, and with no `failure-strategy` the default is `fail`, so one malformed record stopped the consumer, was never committed, and returned on every restart — and because readiness covers the whole service that took the dashboard, REST and the review pipeline down over one transcript line. Three comments asserted it could not happen. Overridden like its three siblings, `failure-strategy: ignore`, and `record` survives a key fault as well as a SQL one — round 1
- [cr-I1 / qa (medium)] The page returned the OLDEST events while three comments called it newest, with no cursor, so on a long run the end — where a failure is — was unreachable. Newest page plus a `since` cursor — round 1
- [cr-I2 / sec-L3] One undecryptable row took out the whole page and the socket snapshot with it, because the only catch was for SQL. Guarded per row, as every other decrypt site in this service already is — round 1
- [cr-I3 / qa (low)] The emitter had no overflow strategy, so the default buffers then FAILS the stream — and it is shared by every concurrent run, so one chatty agent would end transcripts for every run until restart. DROP declared. The asynchronous refusal was also discarded, so the gap warning could not fire for the most likely loss — round 1
- [cr-I4 / rules / sec-L2] `run_event_by_run` was byte-identical to the primary key's index: pure write amplification on the largest table in the schema — round 1
- [qa-1 vacuous] The redelivery test could not fail. With a plain insert the key violation is eaten by `record`'s own catch and one row still remains — and it logged a false "the transcript will have a gap" on every redelivery. It asserts whether the statement RAN — round 1
- [qa-1 gap] The encryption test passed with a constant AAD, so "cannot be replayed under another run" was asserted by nothing. A moved row now proves it, and proves the per-row guard at the same time — round 1
- [cr-S1 / qa-3] The discarding `launch` overload had ZERO production callers and was a documented trap. Deleted rather than documented: a comment is a weaker guard than removing it — round 1
- [rules-1] `catch (Exception)` in the socket, an explicit prohibition — round 1
- [rules-5] A serialisation failure logged at DEBUG, which is off in production, while the same fault threw on the snapshot path — round 1
- [rules-6] No `runId` on the MDC, while four siblings in the same service do exactly that — round 1
- [rules-9 / sec] The sweep interval was hardcoded while the window it serves is configurable, and a zero or negative window would delete every transcript. Configurable and clamped — round 1
- [rules-8] The swallow logged only an exception class name, so a permanent fault looked like a transient one and repeated per event. The throwable is logged once per run — round 1
- [cr-S4] `Tail` inside the socket class made a `factory → ws → factory` cycle. Moved to the package that owns the transcript, matching both existing broadcasters — round 1
- [cr-S5] `push` serialised on every event even with nobody tailing. Subscribers are found first — round 1
- [qa (low)] Past the cap the stream still translated and clipped a record before discarding it. The cap is checked before translating — round 1
- [sec-L4] The socket accepted a tail for a run that does not exist while the REST route 404s, and the two answers mean different things — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)
- [rules-10] Commit bodies wrap at up to 79 rather than 72. Measured against this repository's own last 40 commits, 13% exceed 72, so this is a mild deviation rather than a break with house style; rewrapping going forward is cheaper than a rebase (round 1)
- [rules-11 / cr] `docs/CONTRACT.md` and `docs/DATA-MODEL.md` list none of the four run topics or tables. Confirmed pre-existing from M0 rather than a Task 2 regression, and `docs/factory/ARCHITECTURE.md` — which the factory work is catalogued in — already carries both `cs.run-events` and `run_event` accurately. Belongs to the M1 documentation pass (round 1)

## Open (tracked; not fixed in this round)
- [rules-3] `SPIRE_RUN_TRANSCRIPT_RETENTION_DAYS` is absent from `.env.example`, which this project calls the contract, and ADR-034 names the TTL an operator setting. Due with the M1 documentation pass alongside CLAUDE.md
- [rules-4] No `CLAUDE.md` entry for the new topic, table, endpoint, socket and property. This repository's practice is one entry per milestone at delivery; treated as a gate before PR #96 merges rather than a rewrite of these commits
- [qa-3] Three of the plan's seven Task 2 scenarios remain unwritten: the live-tail delivery test, the socket's viewer/unknown-run case, and "nothing from the run stream enters the event store". The first is the one that would have caught both criticals, and it needs a real subscriber — a faked connection returns whatever endpoint id the test chooses, so it cannot catch the defect that mattered
- [qa (low)] `RunEventRecord` is outside `ContractSchemaSnapshotTest.ROOTS`, so a renamed component breaks the wire silently. It crosses two services
- [qa (medium)] Nothing marks the end of the stream: only the agent channel is transcribed, and a run failing before `observe` produces no transcript at all. A live tail simply stops, which this project already learned is indistinguishable from a lost delivery
- [qa (medium)] The cap keeps the oldest events and drops the newest, which reverses the plan's wording. `RunEventFold` faced the same question in the same package and deliberately kept both ends
- [sec-L1] The per-run cap and the kind vocabulary are enforced only at the producer; the store accepts any sequence and any kind
- [sec-L2] The sweep deletes in one statement, so the first tick after an outage can delete days of rows in one transaction
- [rules-2] The six kind literals have no shared definition, while the wire, the table and a future UI all match on them
- Unrelated flake found during the fix batch — `techdebt/spire-orchestrator/4-2-the-retry-schedule-test-races-the-live-scheduler.md`

## Notes
- **All four lenses proved their claims rather than arguing them.** The two criticals were verified against a running server and against the library jars; the vacuous test was proven by mutation. Three of my own comments asserted framework behaviour the framework does not have.
- **The plan was wrong twice more.** It said Task 2 closes `techdebt/global/3-3-run-event-accumulation-is-unbounded.md` and modifies `DlqTopics`. Both omissions are correct: that entry is about the harness summary's SPI shape, and this topic never dead-letters so it needs no replay mapping. The plan needs correcting so nobody deletes the entry on its authority.
