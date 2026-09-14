# Prepared tracker tasks

M3 accepts an existing specification and a single-step plan. It stores their tracker identities,
content digests and build coordinates, without copying their bodies into work-item history.
The specification and plan must be tickets in the work item's registered source. Reference lookup
resolves a ticket number or key to its stable provider identity through the selected source account.

In the work-item detail, an administrator enters the specification ticket and selects **Read
specification version**. The response shows its current SHA-256 and a single-step JSON plan template.
Put that plan in another tracker ticket's body, with the actual instruction. Enter that plan's key,
the build base branch and full commit, and the configured harness and model. **Check artifact
references** reads both current versions; **Register these versions** validates the plan and stores
the references with the verified operator identity and expected work-item revision.

The plan body is a JSON object with integer `schemaVersion: 1`, `specificationSha256` matching the
selected specification, and exactly one `steps` entry containing a nonempty `id` and `instruction`.
The specification body is nonempty text. Each body is bounded to 49,152 characters; the assembled
prompt is also checked against M2's 65,536-character limit. A failed or mismatched tracker read
grants no authority. A changed artifact requires current registration and a new plan decision.

The same prepared task follows the selected effective modes:

| Profile example | Plan/build result |
|---|---|
| suggest | Records the references and stops before build; no run. |
| assisted | Opens a durable plan gate; its recorded human approval admits one build. |
| autonomous | Accepts the plan without a gate and admits one build. |

Names select no implementation branch. Current labels, admission bounds and the ceiling determine
the modes. The gate binds both artifact identities/digests and the base branch, commit, harness and
model. Replacement supersedes the old gate. Artifacts and current authority are read again before
an unstarted build claim is released.

The dispatch transaction records the phase attempt, associated `factory_run` and uncertain effect
claim before calling the M2 launcher. A definite broker miss may retry that same association; an
unacknowledged send waits for its result or the existing authenticated run dispatch-resolution
action. A terminal result has an encrypted durable inbox. Completion is deduplicated by attempt,
including a crash after the aggregate commits and before the inbox acknowledgement commits.
M2 accounts for one agent call per run that could have spent, not the model's internal call count.
Its existing cause-and-usage classification also identifies failures before anything could be bought;
those consume no call and can be readmitted after repair. Unmeasured potential spend blocks further
work; a missing price or charge after execution is never treated as a known zero.

**Slice 8a execution boundary:** tests replace only the final broker emitter and explicitly declare
the test publication capability. They exercise real artifact reads, transitions, database claims,
M2 assembly and acknowledgement classification. Production item builds remain
`capability_unavailable` until slice 8b supplies a trusted publication hold. Standalone runs retain
their existing path. Production without a verifier stays waiting at verify; test-supplied prior
phase results do not establish an M4 verifier, draft publication or a live-forge journey.
