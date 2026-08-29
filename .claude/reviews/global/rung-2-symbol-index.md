# Code Review State: global / rung-2-symbol-index

Last reviewed: 2026-08-29
Rounds completed: 1

## Resolved (fixed in code; do not re-raise)
- [security/C-1] Two regexes quadratic on whole-file input (unterminated block comment, member-declaration span overlapping its capture class) — replaced by SourceText index scans + 256 KB skip — round 1
- [code-quality/C-2] JavaLanguageSupport excluded imported names from references, so callersOf returned nothing and rung 2 was inert — filter removed, SymbolIndexSeamTest added — round 1
- [security/M-1] No confirmation-fetch budget between an index read and a citation — MAX_CALLER_CONFIRMATIONS=20 — round 1
- [security/M-2] No per-review row ceiling; per-file cap could be written once per changed file — MAX_RECORDED_FILES=100 — round 1
- [security/L-1] SECURITY.md understated the disclosure (identifier inventory + file-level dependency map, cross-repository) — restated, operator levers named — round 1
- [security/L-4] MAX_SNIPPETS + 3 callers overran the code_context slot budget — callers now trim the definition tail — round 1
- [security/L-5] Test SQL built by string concatenation — parameterised — round 1
- [security/L-2] Index-supplied paths untested against the allow-list and traversal guards — two tests added — round 1
- [code-quality/I-7] Confirmed callers never re-recorded, so the freshest rows were pruned first — recordFile on confirmation — round 1
- [code-quality/I-3] Per-file row budget spent DEFINES-first starved the REFERENCES role — MAX_ROWS_PER_ROLE — round 1
- [code-quality/I-4] Over-length identifier failed the batch and discarded the whole file — skipped instead — round 1
- [code-quality/I-5] Set iteration order salted per JVM made truncation non-deterministic — sorted before every cut — round 1
- [code-quality/I-6] Index key lacked the platform, so one workspace name on two SCMs collided — scmType:workspace/slug — round 1
- [code-quality/I-8] Lookups spent on every declaration in a changed file — intersected with the diff's identifiers — round 1
- [code-quality/I-9] Recording ignored the resolution deadline and parsed on after the aggregator gave up — deadline honoured — round 1
- [code-quality/I-10] A path cited as a definition could be cited again as a caller — alreadyCited now read — round 1
- [code-quality/I-11] No kill switch for the feature's first persistent store — SPIRE_SYMBOL_INDEX_ENABLED — round 1
- [rules/R-2..R-8] CodeContextProvider 951 lines, methods over 30 — caller path extracted to CallerLookup (951 -> 750), declaredCallableName/callersOf/resolve split — round 1
- [qa/Q-1] WorkerContextClients never proved the index reached the constructed provider — hasSymbolIndex() + both wiring tests — round 1
- [qa/Q-2] MAX_CALLER_LOOKUPS, MAX_CALLER_CONFIRMATIONS, MAX_ROWS_PER_ROLE, MAX_RECORDED_FILES untested — one test each — round 1
- [qa/Q-3] callSiteSnippet whole-word matching untested (Pricer vs PricerFactory) — round 1
- [qa/Q-4] neverCitesAChangedFileAsItsOwnCaller was vacuous (a declaring file can never confirm as its own caller) — rewritten with a second changed file — round 1
- [qa/Q-5] doesNotCiteAsACallerAFileAlreadyCitedAsADefinition was vacuous (fixture had no imports, so no definitions to duplicate) — rewritten — round 1
- [qa/Q-6] SymbolIndexRetention sweep untested — SymbolIndexRetentionTest — round 1
- [qa/Q-7] TypeScript-only control words (await, typeof, of, delete) untested — aTypeScriptControlWordDeclaresNothing — round 1

## Dismissed (acknowledged, will not fix; agents may escalate with explicit justification)
- (none)
