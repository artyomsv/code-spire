# Factory M3 slice 2 — repository ownership cutover

Slice 1 is closed on fully green commit 363b13d. This change completes the runtime cutover on
top of it; the PR remains draft for M3 slices 3–10.

## Result and dispatch evidence

Accounts retain their UUIDs and encrypted credentials. Repositories own full forge identity,
workspace, slug and explicit REVIEWER/FACTORY bindings. All active SCM credential paths select
through RepositoryAccounts; account workspace is absent from DTOs, forms and runtime SQL.
V61 removes the old UNIQUE and workspace-by-role CHECK, retaining the populated column and
scalar role checks. The architecture guard covers account SQL, alias/SELECT-star mappings and
the removed workspace-only resolver.

| Entry point | Credential selection |
|---|---|
| Signed ingress / IntegrationSaga | Full forge identity and optional explicit UUID, then REVIEWER binding; persist UUID before commands |
| ReviewProviderResolver / WorkerCredentials | Persisted review UUID → REVIEWER; old review-address workspace stays the transport AAD |
| Rerun / retry / result handling / prompt sample | ReviewProviderResolver or WorkerCredentials for the persisted review |
| Manual registration | Parsed URL with canonical forge origin, or explicit repository UUID; contradictory coordinates and unmapped legacy reviews refuse before forge calls |
| Conversation and finding self-loop | Persisted review mapping and REVIEWER binding |
| MachineAccounts / RunResource | Explicit repository UUID → FACTORY, retaining push-login checks |
| FixRunDispatcher | Review's persisted repository → FACTORY; queue with repository UUID |
| FactoryPullRequests | Run's persisted repository → FACTORY at proposal time |
| Serving views | Non-secret account views through the selected repository/role binding, including disabled/missing states |

RepositoryResolverCutoverTest invokes the real entry points with same-path other-forge and
same-forge other-repository decoys. Its observer executes the real SQL lookup and credential
decryption, checks the selected account UUID/plaintext TEST token, then stops at that boundary
before network or spend. It is not an assertion that each path sent a live forge command.
Separate choreography, manual, rerun, prompt, conversation, run and fix suites cover what follows.
The unmapped case proves no credential or command leaves, then explicitly repairs the same
header as its positive control. A real Kafka test proves raw legacy ingress lands durably in
the DLQ with the original topic/type and provenance reason.

Nested GitLab coordinates keep their old review-ID/transport split while repository lookup
canonicalizes the full path at the final slash. No ciphertext or historical ID is rewritten.
Bitbucket account-less token validation accepts an optional same-origin/same-kind repository
UUID; stored account Check derives its namespace from an explicit binding, never old workspace.

## Gateway and operator flow

Gateway V4 adds repository, kind, source and revision metadata without changing existing keys,
ciphertexts, decrypted secrets or rejection history. A production UNIQUE(repository_id,event_kind)
enforces one hook per kind. The fresh-private-schema test upgrades actual V3 rows through V4,
decrypts the retained secret, and sends a correctly signed request through the real HTTP edge.
Wrong-kind and same-provider wrong-repository requests are discriminating negative cases.
Duplicate API creation returns a repairable 409. ISSUE creation and update require a source;
SCM ingress does not accept ISSUE events in this slice.

RepositoryDelivery carries verified provenance on cs.repository-integration. REVIEWER enters
the review lifecycle; FACTORY is forwarded to cs.repository-activity for later work-item consumers.
Organization hooks cannot auto-enroll repositories. A verified unregistered event produces
durable Attention naming repository, origin and registration, with a prefilled Register action.
Unconfirmed origins remain explicit repair states.

The main repository screen owns coordinates, role bindings and one hook control per kind.
No-hook registration, missing/disabled accounts, gateway outages and lost save responses have
named states and retries. Retries read the existing hook before creating or rotating anything.
Legacy organization hooks and old deep links remain usable. Repair of an actual gateway hook
updates that retained registration at its owner so future deliveries carry the chosen origin;
history-only repair uses the orchestrator bridge. Changed forge/scope/target metadata refuses
a stale repair. Unknown origins are never inferred merely from matching namespaces.

## Criterion 7 and isolated mutations

The exact required witnesses are RepositoryResourceTest.repositoryOwnsWorkspaceAndRoleBindings,
RepositoryWebhookKindsTest.refusesASecondWebhookForTheSameKind, RepositoryDetail's
“shows workspace selected accounts and one webhook per event kind”, and SettingsProviders.form's
“does not offer workspace on an account”. The UNIQUE mutant edits production V4 on a fresh
private Flyway schema, then inserts through WebhookRepoRegistry; no ALTER touches dev.

62 distinct slice 2 mutations: 42 Java, 15 UI, plus the five earlier full-identity
lookup mutations. Each compiling mutant fails exactly one selected test and the restored case
passes. Sources are restored from scratch copies and their SHA-256 checked, never from git.
Java uses the module test task with --rerun --tests <method> --no-parallel; UI uses vitest with
one exact case. Full affected suites run after restoration in the final tiers.

The first stored-account-check mutant reached the wrong adapter endpoint and threw an API
exception: this was not counted as an assertion kill. The witness now asserts the selected
binding's check completes successfully before verifying its exact authorized request; rerunning
the same mutant produces one assertion failure and restores green. Initial runner argument
errors likewise are not counted as mutation kills.

The first five lookup mutations remove kind/origin/workspace/slug equality or origin
canonicalization individually. Their selected RepositoryLookupTest methods are
samePathOnAnotherForgeKindCannotMatch, samePathOnAnotherOriginCannotMatch,
anotherNamespaceCannotMatch, anotherSlugCannotMatch and canonicalOriginFindsRegisteredRepository.
Their shared original SHA-256 is
4CFC8B7740343CA99B6CBD0D76B3EBA67FFD7E8D28D1F2B842F4841DBE9B12A8.

The tables record the original scratch hash for each subsequent mutation. Each row means
one selected assertion failure followed by one passing restored case; JSON/XML/log files are
under .handoff/s2-java-* and .handoff/s2-* locally.

| Java mutation | Selected witness | Original SHA-256 |
|---|---|---|
| binding_filter | RepositoryResourceTest.repositoryOwnsWorkspaceAndRoleBindings | 0EC390C51F6E08EEBA37BD1E7F33779803688D62D164147B0F6C08FF879678B7 |
| kind_unique | RepositoryWebhookKindsTest.refusesASecondWebhookForTheSameKind | 85C6A85A1DAAC63BB7C03F05D78728435593109145269F9B854500374C0CB628 |
| scope | RepositoryWebhookKindsTest.validSignatureCannotCrossRepositoryScope | E09D2A501E6F9CF4BD2A51E2A0A4E0CDE37D5AC17D78AD451BA1FC7A87146847 |
| gateway_kind | RepositoryWebhookKindsTest.preservesLegacyKeyAndRejectsWrongKind | E09D2A501E6F9CF4BD2A51E2A0A4E0CDE37D5AC17D78AD451BA1FC7A87146847 |
| workspace_read | AccountWorkspaceIsUnusedTest.noProductionCodeReadsLegacyAccountWorkspace | 1523A575467F9F52BCD5E81251F78401BBF5DC82A1FBD93ABD37AC5CF78AD6FD |
| unregistered_attention | UnregisteredRepositoryAttentionTest.namesRepositoryOriginAndRegistration | 1082F772345144F671BED4629CDF6E77D545B404BC4828CEA468DE25AD5AD8A3 |
| nested_lookup | RepositoryLookupTest.legacyNestedReviewCoordinatesFindTheCanonicalRepository | 0EC390C51F6E08EEBA37BD1E7F33779803688D62D164147B0F6C08FF879678B7 |
| review_role | RepositoryResolverCutoverTest.allDispatchPathsUseTheSelectedRepository | EECEB4D34A97387046FCB314A30ADB6B007E4AB67B3263889588BFEFD6F8CEAF |
| factory_role | RepositoryResolverCutoverTest.allDispatchPathsUseTheSelectedRepository | 47C5249F3677130A4E66243EA63572F0012350F093597C01DD636E0CD01666EC |
| unmapped_manual | RepositoryResolverCutoverTest.unmappedLegacyReviewCannotDispatch | 4C4F3DFC0598F4A5BCF0075ED852EDB5D184598EE2B5FB3827EA0FF6E0C098BE |
| unmapped_claim | RepositoryResolverCutoverTest.unmappedLegacyReviewCannotDispatch | B5131957AD156893BCC41232794CAE56E3C97EE64DF85A072A45FFDEC7212413 |
| command_mapping | ConversationFindingSagaTest.findingOnAnUnregisteredPrFilesNothingAndConfirmsNothing | AED0F96B4FC5E6BA8D21FCA5BCD08EE71895EF0188FB676A3C3344F0D3700002 |
| ingress_kind | RepositoryIngressRoutingTest.aFactoryHookCannotDeliverReviewerCommands | 5E059DD1B9D048781CD743CC2F3901FCDFD115BEE95FE0C34E6C0D6A8557A24E |
| ingress_disabled | RepositoryIngressRoutingTest.aDisabledRepositoryCannotProcessAnAlreadyVerifiedDelivery | 5E059DD1B9D048781CD743CC2F3901FCDFD115BEE95FE0C34E6C0D6A8557A24E |
| ingress_id | RepositoryIngressRoutingTest.anExplicitRepositoryIdCannotNameAnotherMatchingPath | 5E059DD1B9D048781CD743CC2F3901FCDFD115BEE95FE0C34E6C0D6A8557A24E |
| reply_scope | RepositoryIngressRoutingTest.aReplyCannotNameAnotherReviewThanItsRepositoryCoordinates | 5E059DD1B9D048781CD743CC2F3901FCDFD115BEE95FE0C34E6C0D6A8557A24E |
| factory_routing | RepositoryIngressRoutingTest.factoryActivityNeverEntersTheReviewLifecycle | 5E059DD1B9D048781CD743CC2F3901FCDFD115BEE95FE0C34E6C0D6A8557A24E |
| missing_origin | RepositoryIngressRoutingTest.aMissingOriginIsAttentionEvenWhenThePathIsRegistered | 5E059DD1B9D048781CD743CC2F3901FCDFD115BEE95FE0C34E6C0D6A8557A24E |
| delivery_dlq | DlqTopicsTest.repositoryDeliveriesReplayWithTheirProvenance | 2F04F887A0C79715A4BE3B7955768452839CACC65268B106AD512A82E5DD9C52 |
| legacy_dlq | RepositoryResolverCutoverTest.legacyWireDeliveryIsDeadLetteredWithItsProvenanceProblem | AED0F96B4FC5E6BA8D21FCA5BCD08EE71895EF0188FB676A3C3344F0D3700002 |
| validation_origin | ProviderIdentityResolverTest.validationRepositoryMustBelongToTheAccountsForgeOrigin | 3C5983C6FEBE6A0BCD3E2CAE0C40D714FCCECE9376489C233F16FC67AC4284F4 |
| validation_kind | ProviderIdentityResolverTest.validationRepositoryMustBelongToTheAccountsForgeKind | 3C5983C6FEBE6A0BCD3E2CAE0C40D714FCCECE9376489C233F16FC67AC4284F4 |
| simulator_id | DevSimulatorRepositoryTest.simulationRequiresAnExplicitRepository | 10ABA0AE719CA0FD2705651647D9054F618AB7527AA35D95E13A8A30E3F5FE38 |
| simulator_scope | DevSimulatorRepositoryTest.simulationCarriesItsSelectedRepositoryAndCannotUseRealNamespaces | 10ABA0AE719CA0FD2705651647D9054F618AB7527AA35D95E13A8A30E3F5FE38 |
| simulator_stub | DevSimulatorRepositoryTest.simulationRequiresStubMode | 10ABA0AE719CA0FD2705651647D9054F618AB7527AA35D95E13A8A30E3F5FE38 |
| duplicate_http | WebhookRepoResourceTest.duplicateKindReturnsARepairableConflict | 6F25000D2E242EB5A3847BE1953C6B66C2CEF5DDEAAA3B4CF01B6C0ABAE1EDAC |
| issue_create | WebhookRepoResourceTest.issueKindRequiresASourceOnCreate | 6F25000D2E242EB5A3847BE1953C6B66C2CEF5DDEAAA3B4CF01B6C0ABAE1EDAC |
| issue_update | WebhookRepoResourceTest.issueKindRequiresASourceOnUpdateAndPreservesAnExistingSource | 6F25000D2E242EB5A3847BE1953C6B66C2CEF5DDEAAA3B4CF01B6C0ABAE1EDAC |
| delivery_identity | RepositoryDeliveryTest.requiresARepositoryOrRegistration | 8F42F5338D7B3CB5CC99FE92781FA14A60E9903B24555B89F74AF6ADF8337659 |
| delivery_revision | RepositoryDeliveryTest.requiresAPositiveRegistrationRevision | 8F42F5338D7B3CB5CC99FE92781FA14A60E9903B24555B89F74AF6ADF8337659 |
| delivery_id | RepositoryDeliveryTest.requiresANonblankDeliveryId | 8F42F5338D7B3CB5CC99FE92781FA14A60E9903B24555B89F74AF6ADF8337659 |
| check_binding | ProviderIdentityResolverTest.storedAccountChecksUseAnExplicitRepositoryBinding | 3C5983C6FEBE6A0BCD3E2CAE0C40D714FCCECE9376489C233F16FC67AC4284F4 |
| manual_request_id | RepositoryRequestIdentityTest.manualRequiresExplicitRepositoryIdentity | 72A13F65CB67F7EEF5D1D68F6827D57B0ADD6D7E297C367351826AAB0DDF3F16 |
| manual_request_workspace | RepositoryRequestIdentityTest.manualRejectsContradictoryCoordinates | 72A13F65CB67F7EEF5D1D68F6827D57B0ADD6D7E297C367351826AAB0DDF3F16 |
| manual_request_slug | RepositoryRequestIdentityTest.manualRejectsContradictoryCoordinates | 72A13F65CB67F7EEF5D1D68F6827D57B0ADD6D7E297C367351826AAB0DDF3F16 |
| manual_request_providerType | RepositoryRequestIdentityTest.manualRejectsContradictoryCoordinates | 72A13F65CB67F7EEF5D1D68F6827D57B0ADD6D7E297C367351826AAB0DDF3F16 |
| run_request_id | RepositoryRequestIdentityTest.runRequiresExplicitRepositoryIdentity | CDC238E5DBD09120082950CB86E1D5BA35E958FDA30479B60A80990A22FDA1D5 |
| run_request_workspace | RepositoryRequestIdentityTest.runRejectsContradictoryCoordinates | CDC238E5DBD09120082950CB86E1D5BA35E958FDA30479B60A80990A22FDA1D5 |
| run_request_slug | RepositoryRequestIdentityTest.runRejectsContradictoryCoordinates | CDC238E5DBD09120082950CB86E1D5BA35E958FDA30479B60A80990A22FDA1D5 |
| run_request_providerType | RepositoryRequestIdentityTest.runRejectsContradictoryCoordinates | CDC238E5DBD09120082950CB86E1D5BA35E958FDA30479B60A80990A22FDA1D5 |
| legacy_kind_default | RepositoryRegistrationTest.legacyMetadataDefaultsToReviewerWithoutInventingRepositoryOrSource | 6C8D5FEF66427F9B117CABFAA16A2FB8B0C34AAB075EBC6DEF858F1E61493011 |
| delivery_discriminator | RepositoryDeliveryTest.verifiedEnvelopeRoundTripsWithProvenance | 8F42F5338D7B3CB5CC99FE92781FA14A60E9903B24555B89F74AF6ADF8337659 |

| UI mutation | Selected witness | Original SHA-256 |
|---|---|---|
| detail_workspace | RepositoryDetail: shows workspace selected accounts and one webhook per event kind | FABE9681EB0DBA21C44EF10A43ECCE676520A107A114E7CB2EF380E7753A2D46 |
| detail_accounts | same criterion 7 case; hide accounts | FABE9681EB0DBA21C44EF10A43ECCE676520A107A114E7CB2EF380E7753A2D46 |
| detail_hooks | same criterion 7 case; hide hooks | FABE9681EB0DBA21C44EF10A43ECCE676520A107A114E7CB2EF380E7753A2D46 |
| account_workspace | SettingsProviders.form: does not offer workspace on an account | EE780071CEFFE3820D947CCABDD9B5FCF2B219B5274E54111CA3D828E39D216D |
| lost_response | RepositoryDetail: recovers a lost webhook response without creating a duplicate | FABE9681EB0DBA21C44EF10A43ECCE676520A107A114E7CB2EF380E7753A2D46 |
| legacy_repair | RepositoryDetail: requires explicit origin repair before creating beside an unresolved legacy hook | FABE9681EB0DBA21C44EF10A43ECCE676520A107A114E7CB2EF380E7753A2D46 |
| validation_origin | SettingsProviders.form: validates an account-less token against an explicit same-origin repository | EE780071CEFFE3820D947CCABDD9B5FCF2B219B5274E54111CA3D828E39D216D |
| validation_kind | same validation case; remove kind match | EE780071CEFFE3820D947CCABDD9B5FCF2B219B5274E54111CA3D828E39D216D |
| validation_reset | SettingsProviders.form: clears the validation repository when the account origin changes | EE780071CEFFE3820D947CCABDD9B5FCF2B219B5274E54111CA3D828E39D216D |
| attention_prefill | RepositoryRegistryPage: prefills registration from Attention while the repository page is already mounted | A5EA9BB78A9AC73243533A353E768A9AFCC88922746F6EBF4032D9A475915315 |
| pending_owner | RepositoryRegistryPage: repairs a gateway registration at its owner so new deliveries carry the selected origin | 147CC4284502664C71BA2F88BA8AA60864F2BDF813302A2F9737775B26AA50E2 |
| pending_type | RepositoryRegistryPage: refuses to repair a registration whose current forge or coordinates changed | 147CC4284502664C71BA2F88BA8AA60864F2BDF813302A2F9737775B26AA50E2 |
| pending_scope | same stale-repair case; remove scope check | 147CC4284502664C71BA2F88BA8AA60864F2BDF813302A2F9737775B26AA50E2 |
| pending_target | same stale-repair case; remove target check | 147CC4284502664C71BA2F88BA8AA60864F2BDF813302A2F9737775B26AA50E2 |
| pending_origin | same stale-repair case; remove origin check | 147CC4284502664C71BA2F88BA8AA60864F2BDF813302A2F9737775B26AA50E2 |

## Final verification and real rollout

Forced testFast then testServices passed sequentially with JDK 25 and --no-parallel:
3048 Java tests across 357 suites, zero failures and 1 existing Windows symlink privilege skip. Assemble passed. No live run worker was started; the service tier's isolated worker tests ran under the Docker lock. UI: 630 tests across 77 files and production build passed. The final full UI run used
--maxWorkers=4 after one unchanged role test timed out under default concurrency; its isolated
three-case file also passed. Local pinned Semgrep 1.172.0 scanned 1,090 files with 442 rules and
zero findings on the final runtime sources, including the UI repair and wire-contract tests.
Two architecture checks initially inspected the scanner's duplicate source copy inside .handoff;
moving that completed snapshot outside the worktree removed the duplicate inputs.

The existing backup is unchanged: 182 objects, 492,480 bytes, SHA-256
7660D8EAE4110141887BD565747D45D1AA2621025A157D36741F70B6E65A1051.
After rebuilding gateway first, then orchestrator and UI with --build --no-deps, readiness
was UP and UI returned HTTP 200. Gateway V4 and orchestrator V61 are live. All three V4
snapshots were acknowledged and the live DLQ remained empty.

Post-cutover counts are exactly 6 accounts / 37 reviews / 85 findings / 14 runs / 3 hooks.
The six retained workspace values and all hook rejection metadata match their baseline.
The encrypted comparisons matched all 9 account/context entries and all 12 webhook entries:
no missing, added or changed entries. Authenticated API reads showed six repositories, eight
selected role bindings, six workspace-free accounts, three retained REVIEWER hooks and the
three explicit origin repairs. This was a read-only proof; no synthetic live row was introduced.

Live image IDs from the rebuilt source:

- /spire-gateway-dev sha256:7d747d684ee6fccf0fb87583ab1070f5eb12673700bd2731a341671550aa9ed1
- /spire-orchestrator-dev sha256:be662d7084cef6fd3c1ccfd06d1a0b8119740598a206379487274b8da8f0c150
- /spire-ui-dev sha256:44c64ad66bf86aa5a7ce31ee1d67f1dbb2717cee385b2149d8cfd434969b106e
The encrypted account/context
baseline has 9 entries; the separate webhook baseline has 12 entries (key, ciphertext,
decrypted secret and provider/scope/target for each of three real hooks).

The three missing origins still require operator confirmation. They remain pending by design,
so their deliveries cannot dispatch until repaired; they are not reported as live routing proofs. A read-only attempt to find
the retained keys in forge webhook settings returned GitHub 404, Bitbucket 403 and GitLab 403;
none confirms an origin. No webhook origin, key or secret was changed by that probe. Later M3
work-item/ISSUE behavior and native forge permission measurements remain outside this slice.
No synthetic live rows, additional database dump, or live run-worker start were made.

## Exact mutation edits

Paths and line numbers below refer to the recorded scratch snapshot. A dash means the replacement is empty.
Every recorded restored hash equals its original hash in the tables above. The selected mutant has
one failed case and zero skipped cases; the selected restored case passes. Baselines are the
successful selected/full suites recorded above and in the corresponding local logs.

| Mutation | Production path and original line | From → to | Exact selector |
|---|---|---|---|
| binding_filter | spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryRegistry.java:28 | AND ra.role='REVIEWER' → — | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryResourceTest.repositoryOwnsWorkspaceAndRoleBindings |
| kind_unique | spire-gateway/src/main/resources/db/migration/V4__repository_webhook_kinds.sql:8 | ALTER TABLE webhook_repo ADD CONSTRAINT webhook_repo_repository_kind_key UNIQUE (repository_id,event_kind); → -- TEST mutation: remove repository/kind uniqueness | :spire-gateway:test --rerun --tests dev.codespire.gateway.registry.RepositoryWebhookKindsTest.refusesASecondWebhookForTheSameKind |
| scope | spire-gateway/src/main/java/dev/codespire/gateway/RegistryWebhookEdge.java:161 | eventRepo.full().equals(reg.target()) → true | :spire-gateway:test --rerun --tests dev.codespire.gateway.registry.RepositoryWebhookKindsTest.validSignatureCannotCrossRepositoryScope |
| gateway_kind | spire-gateway/src/main/java/dev/codespire/gateway/RegistryWebhookEdge.java:117 | if (!repo.eventKind().accepts(event)) → if (false) | :spire-gateway:test --rerun --tests dev.codespire.gateway.registry.RepositoryWebhookKindsTest.preservesLegacyKeyAndRejectsWrongKind |
| workspace_read | spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryAccounts.java:39 | SELECT a.account_id, r.scm_type → SELECT (SELECT workspace FROM scm_provider WHERE id=a.account_id) legacy_workspace, a.account_id, r.scm_type | :spire-arch:test --rerun --tests dev.codespire.arch.AccountWorkspaceIsUnusedTest.noProductionCodeReadsLegacyAccountWorkspace |
| unregistered_attention | spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/UnregisteredRepositoryEvents.java:31 | ps.executeUpdate(); → ; | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.UnregisteredRepositoryAttentionTest.namesRepositoryOriginAndRegistration |
| nested_lookup | spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryRegistry.java:64 | statement.setString(3, fullPath.substring(0, leaf)); → statement.setString(3, workspace); | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryLookupTest.legacyNestedReviewCoordinatesFindTheCanonicalRepository |
| review_role | spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ReviewProviderResolver.java:28 | accounts.resolve(id, ProviderRole.REVIEWER) → accounts.resolve(id, ProviderRole.FACTORY) | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryResolverCutoverTest.allDispatchPathsUseTheSelectedRepository |
| factory_role | spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/MachineAccounts.java:38 | return accounts.resolve(repositoryId, ProviderRole.FACTORY)<br>                .filter → return accounts.resolve(repositoryId, ProviderRole.REVIEWER)<br>                .filter | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryResolverCutoverTest.allDispatchPathsUseTheSelectedRepository |
| unmapped_manual | spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/ManualRegisterResource.java:82 | if (projection.registered(requestedReviewId) → if (false && projection.registered(requestedReviewId) | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryResolverCutoverTest.unmappedLegacyReviewCannotDispatch |
| unmapped_claim | spire-orchestrator/src/main/java/dev/codespire/orchestrator/readmodel/ReviewProjection.java:105 | WHERE review_status.repository_id=EXCLUDED.repository_id → WHERE review_status.repository_id=EXCLUDED.repository_id OR review_status.repository_id IS NULL | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryResolverCutoverTest.unmappedLegacyReviewCannotDispatch |
| command_mapping | spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/IntegrationSaga.java:123 | } else if (!projection.repositoryIdOf(reviewId).filter(repositoryId::equals).isPresent()) → } else if (false && !projection.repositoryIdOf(reviewId).filter(repositoryId::equals).isPresent()) | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.pipeline.ConversationFindingSagaTest.findingOnAnUnregisteredPrFilesNothingAndConfirmsNothing |
| ingress_kind | spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryIngressConsumer.java:33 | if (!delivery.eventKind().accepts(delivery.event())) return; → if (false) return; | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryIngressRoutingTest.aFactoryHookCannotDeliverReviewerCommands |
| ingress_disabled | spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryIngressConsumer.java:42 | !repository.enabled() \|\| → false \|\| | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryIngressRoutingTest.aDisabledRepositoryCannotProcessAnAlreadyVerifiedDelivery |
| ingress_id | spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryIngressConsumer.java:43 | !repository.id().equals(delivery.repositoryId()) → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryIngressRoutingTest.anExplicitRepositoryIdCannotNameAnotherMatchingPath |
| reply_scope | spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryIngressConsumer.java:46 | !dev.codespire.contract.event.ReviewIds.parse(reply.reviewId()).repo().equals(delivery.repo()) → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryIngressRoutingTest.aReplyCannotNameAnotherReviewThanItsRepositoryCoordinates |
| factory_routing | spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryIngressConsumer.java:49 | } else if (delivery.eventKind() == RepositoryEventKind.FACTORY) → } else if (false) | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryIngressRoutingTest.factoryActivityNeverEntersTheReviewLifecycle |
| missing_origin | spire-orchestrator/src/main/java/dev/codespire/orchestrator/repository/RepositoryIngressConsumer.java:34 | delivery.forgeOrigin() == null ? Optional.empty() → false ? Optional.empty() | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryIngressRoutingTest.aMissingOriginIsAttentionEvenWhenThePathIsRegistered |
| delivery_dlq | spire-orchestrator/src/main/java/dev/codespire/orchestrator/dlq/DlqTopics.java:57 | if ("RepositoryDelivery".equals(type)) return "cs.repository-integration"; → if ("RepositoryDelivery".equals(type)) return "cs.commands"; | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.dlq.DlqTopicsTest.repositoryDeliveriesReplayWithTheirProvenance |
| legacy_dlq | spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/IntegrationSaga.java:105 | throw new IllegalStateException("Legacy ingress has no verified repository identity; redeliver through its registered webhook"); → return; | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.repository.RepositoryResolverCutoverTest.legacyWireDeliveryIsDeadLetteredWithItsProvenanceProblem |
| validation_origin | spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderIdentityResolver.java:50 | !repository.forgeOrigin().equals(dev.codespire.contract.scm.ForgeOrigin.of(in.baseUrl())) → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.provider.ProviderIdentityResolverTest.validationRepositoryMustBelongToTheAccountsForgeOrigin |
| validation_kind | spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderIdentityResolver.java:49 | !repository.scmType().equals(in.type()) → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.provider.ProviderIdentityResolverTest.validationRepositoryMustBelongToTheAccountsForgeKind |
| simulator_id | spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/DevSimulatorResource.java:50 | if (repositoryId == null) throw → if (false) throw | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.DevSimulatorRepositoryTest.simulationRequiresAnExplicitRepository |
| simulator_scope | spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/DevSimulatorResource.java:52 | if (!repository.workspace().startsWith("TEST-")) → if (false) | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.DevSimulatorRepositoryTest.simulationCarriesItsSelectedRepositoryAndCannotUseRealNamespaces |
| simulator_stub | spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/DevSimulatorResource.java:46 | if (!stubScm) → if (false) | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.DevSimulatorRepositoryTest.simulationRequiresStubMode |
| duplicate_http | spire-gateway/src/main/java/dev/codespire/gateway/registry/WebhookRepoResource.java:91 | "23505".equals(sql.getSQLState()) → false | :spire-gateway:test --rerun --tests dev.codespire.gateway.WebhookRepoResourceTest.duplicateKindReturnsARepairableConflict |
| issue_create | spire-gateway/src/main/java/dev/codespire/gateway/registry/WebhookRepoResource.java:68 | in.eventKind() == dev.codespire.contract.event.RepositoryEventKind.ISSUE && in.sourceId() == null → false | :spire-gateway:test --rerun --tests dev.codespire.gateway.WebhookRepoResourceTest.issueKindRequiresASourceOnCreate |
| issue_update | spire-gateway/src/main/java/dev/codespire/gateway/registry/WebhookRepoResource.java:81 | kind == dev.codespire.contract.event.RepositoryEventKind.ISSUE && sourceId == null → false | :spire-gateway:test --rerun --tests dev.codespire.gateway.WebhookRepoResourceTest.issueKindRequiresASourceOnUpdateAndPreservesAnExistingSource |
| delivery_identity | spire-contract/src/main/java/dev/codespire/contract/event/RepositoryDelivery.java:19 | registrationId == null && repositoryId == null → false | :spire-contract:test --rerun --tests dev.codespire.contract.event.RepositoryDeliveryTest.requiresARepositoryOrRegistration |
| delivery_revision | spire-contract/src/main/java/dev/codespire/contract/event/RepositoryDelivery.java:20 | registrationId != null && registrationRevision < 1 → false | :spire-contract:test --rerun --tests dev.codespire.contract.event.RepositoryDeliveryTest.requiresAPositiveRegistrationRevision |
| delivery_id | spire-contract/src/main/java/dev/codespire/contract/event/RepositoryDelivery.java:18 | deliveryId == null \|\| deliveryId.isBlank() → false | :spire-contract:test --rerun --tests dev.codespire.contract.event.RepositoryDeliveryTest.requiresANonblankDeliveryId |
| check_binding | spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderIdentityResolver.java:59 | .filter(repository -> (repository.reviewer() != null && p.id().equals(repository.reviewer().id()))<br>                        \|\| (repository.factory() != null && p.id().equals(repository.factory().id()))) → .filter(repository -> true) | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.provider.ProviderIdentityResolverTest.storedAccountChecksUseAnExplicitRepositoryBinding |
| manual_request_id | spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/ManualRegisterResource.java:187 | req.repositoryId() == null → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.RepositoryRequestIdentityTest.manualRequiresExplicitRepositoryIdentity |
| manual_request_workspace | spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/ManualRegisterResource.java:192 | req.workspace() != null && !req.workspace().equals(repository.workspace()) → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.RepositoryRequestIdentityTest.manualRejectsContradictoryCoordinates |
| manual_request_slug | spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/ManualRegisterResource.java:193 | req.slug() != null && !req.slug().equals(repository.slug()) → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.RepositoryRequestIdentityTest.manualRejectsContradictoryCoordinates |
| manual_request_providerType | spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/ManualRegisterResource.java:194 | req.providerType() != null && !req.providerType().equals(repository.scmType()) → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.RepositoryRequestIdentityTest.manualRejectsContradictoryCoordinates |
| run_request_id | spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunResource.java:125 | req.repositoryId() == null → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.RepositoryRequestIdentityTest.runRequiresExplicitRepositoryIdentity |
| run_request_workspace | spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunResource.java:130 | req.workspace() != null && !req.workspace().equals(repository.workspace()) → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.RepositoryRequestIdentityTest.runRejectsContradictoryCoordinates |
| run_request_slug | spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunResource.java:131 | req.slug() != null && !req.slug().equals(repository.slug()) → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.RepositoryRequestIdentityTest.runRejectsContradictoryCoordinates |
| run_request_providerType | spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunResource.java:132 | req.providerType() != null && !req.providerType().equals(repository.scmType()) → false | :spire-orchestrator:test --rerun --tests dev.codespire.orchestrator.ingress.RepositoryRequestIdentityTest.runRejectsContradictoryCoordinates |
| legacy_kind_default | spire-contract/src/main/java/dev/codespire/contract/event/RepositoryRegistration.java:20 | if (eventKind == null) eventKind = RepositoryEventKind.REVIEWER; → if (eventKind == null) eventKind = RepositoryEventKind.FACTORY; | :spire-contract:test --rerun --tests dev.codespire.contract.RepositoryRegistrationTest.legacyMetadataDefaultsToReviewerWithoutInventingRepositoryOrSource |
| delivery_discriminator | spire-contract/src/main/java/dev/codespire/contract/event/RepositoryDelivery.java:10 | JsonTypeName("RepositoryDelivery") → JsonTypeName("TEST-WrongDelivery") | :spire-contract:test --rerun --tests dev.codespire.contract.event.RepositoryDeliveryTest.verifiedEnvelopeRoundTripsWithProvenance |
| detail_workspace | spire-ui/src/components/repositories/RepositoryDetail.tsx:91 | <section aria-label="Workspace"> → <section hidden aria-label="Workspace"> | vitest run src/components/repositories/RepositoryDetail.test.tsx -t 'shows workspace selected accounts and one webhook per event kind' |
| detail_accounts | spire-ui/src/components/repositories/RepositoryDetail.tsx:92 | <section aria-label="Selected accounts"> → <section hidden aria-label="Selected accounts"> | vitest run src/components/repositories/RepositoryDetail.test.tsx -t 'shows workspace selected accounts and one webhook per event kind' |
| detail_hooks | spire-ui/src/components/repositories/RepositoryDetail.tsx:98 | <section aria-label="Webhooks"> → <section hidden aria-label="Webhooks"> | vitest run src/components/repositories/RepositoryDetail.test.tsx -t 'shows workspace selected accounts and one webhook per event kind' |
| account_workspace | spire-ui/src/components/ProviderFormModal.tsx:237 | <AccountCredentialFields fields={fields} → <input aria-label="Workspace" defaultValue="TEST-workspace" /><AccountCredentialFields fields={fields} | vitest run src/components/SettingsProviders.form.test.tsx -t 'does not offer workspace on an account' |
| lost_response | spire-ui/src/components/repositories/RepositoryDetail.tsx:45 | if (existing) { → if (existing && false) { | vitest run src/components/repositories/RepositoryDetail.test.tsx -t 'recovers a lost webhook response without creating a duplicate' |
| legacy_repair | spire-ui/src/components/repositories/RepositoryDetail.tsx:48 | } else if (legacy) { → } else if (legacy && false) { | vitest run src/components/repositories/RepositoryDetail.test.tsx -t 'requires explicit origin repair before creating beside an unresolved legacy hook' |
| validation_origin | spire-ui/src/components/ProviderFormModal.tsx:245 | repository.forgeOrigin === new URL(baseUrl).origin → true | vitest run src/components/SettingsProviders.form.test.tsx -t 'validates an account-less token against an explicit same-origin repository' |
| validation_kind | spire-ui/src/components/ProviderFormModal.tsx:245 | repository.scmType === type && → — | vitest run src/components/SettingsProviders.form.test.tsx -t 'validates an account-less token against an explicit same-origin repository' |
| validation_reset | spire-ui/src/components/ProviderFormModal.tsx:233 | patch({ baseUrl: e.target.value }); setValidationRepositoryId(''); → patch({ baseUrl: e.target.value }); | vitest run src/components/SettingsProviders.form.test.tsx -t 'clears the validation repository when the account origin changes' |
| attention_prefill | spire-ui/src/components/repositories/RepositoryForm.tsx:21 | prefill.get('workspace') → null | vitest run src/components/repositories/RepositoryRegistryPage.test.tsx -t 'prefills registration from Attention while the repository page is already mounted' |
| pending_owner | spire-ui/src/components/repositories/RepositoryPending.tsx:22 | if (hook) { → if (hook && false) { | vitest run src/components/repositories/RepositoryRegistryPage.test.tsx -t 'repairs a gateway registration at its owner so new deliveries carry the selected origin' |
| pending_type | spire-ui/src/components/repositories/RepositoryPending.tsx:23 | hook.providerType !== repository.scmType → false | vitest run src/components/repositories/RepositoryRegistryPage.test.tsx -t 'refuses to repair a registration whose current forge or coordinates changed' |
| pending_scope | spire-ui/src/components/repositories/RepositoryPending.tsx:23 | hook.scope !== 'repo' → false | vitest run src/components/repositories/RepositoryRegistryPage.test.tsx -t 'refuses to repair a registration whose current forge or coordinates changed' |
| pending_target | spire-ui/src/components/repositories/RepositoryPending.tsx:24 | hook.target !== &#96;${repository.workspace}/${repository.slug}&#96; → false | vitest run src/components/repositories/RepositoryRegistryPage.test.tsx -t 'refuses to repair a registration whose current forge or coordinates changed' |
| pending_origin | spire-ui/src/components/repositories/RepositoryPending.tsx:25 | hook.forgeOrigin !== repository.forgeOrigin → false | vitest run src/components/repositories/RepositoryRegistryPage.test.tsx -t 'refuses to repair a registration whose current forge or coordinates changed' |
