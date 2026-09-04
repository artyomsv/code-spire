# Memory index

- [Code Spire test-gap pattern](project_code_spire_test_gap_pattern.md) — wiring/ordering is what goes unasserted: dispatch classes, @Observes/@Scheduled declarations, varargs guards proven for one element, and half-overridden fakes all pass green
- [Mutation-testing restore discipline](feedback_mutation_testing_restore_discipline.md) — probe on a git-archive copy in the scratchpad; git checkout as a restore step is forbidden
- [Gradle --rerun is required](feedback_gradle_rerun_required.md) — attach --rerun to EACH task; a trailing one binds only to the last and 63 tasks report UP-TO-DATE
- [Verify the tree before each build](feedback_verify_tree_before_each_build.md) — another session edits this worktree; a clean check minutes ago proves nothing
- [Quarkus WebSocket traps](project_quarkus_websocket_traps.md) — path params match ONE segment, and findByEndpointId wants the FQCN; both fail silently
- [SmallRye Kafka failure defaults](project_smallrye_kafka_failure_defaults.md) — omitted failure-strategy means FAIL, and ObjectMapperDeserializer throws on bad JSON
- [llm_charge readers are review-shaped](project_llm_charge_readers_are_review_shaped.md) — the two cost attention queries carry no subject_kind filter, so every new charge subject lights a false row
- [SmallRye @Blocking is ordered by default](project_smallrye_blocking_ordered_default.md) — the common-annotation @Blocking sets ordered=TRUE; only the reactive-messaging one has the attribute
- [Run-worker control topic is unasserted](project_run_worker_control_topic_gaps.md) — rename @Incoming to the command channel and all 16 listener tests stay green
- [KafkaSends seam is untested](project_kafkasends_seam_untested.md) — awaitAck's three-way exception mapping has no test; swapping the timeout branch leaves 949 orchestrator tests green
- [V51's DO block is silent on zero match](project_v51_do_block_silent_on_zero_match.md) — SELECT INTO without STRICT reproduces the exact silence the migration was written to avoid
- [Nothing produces CREDENTIAL_REJECTED](project_run_failure_cause_has_no_credential_producer.md) — the run pipeline's cause vocabulary omits it, so FR-F12's automatic key-rejection path is inert
- [Fast orchestrator mutation loop](feedback_fast_orchestrator_mutation_loop.md) — git-archive copy + --tests filter = 65s per mutation, so a full vacuity sweep is cheap
- [Credential-refusal guard allowlists its own producer](project_credential_refusal_guard_allowlists_its_own_producer.md) — an ALIASES entry onto CREDENTIAL_REJECTED passes the whole fast tier; UNVERIFIED.md §A1 cites this guard as sound
- [SecretScrub spelling traps](project_secret_scrub_spelling_traps.md) — a %40 fixture cannot discriminate a decode-only regression, and deleting a startup refusal moved a URLDecoder throw onto the run-launch path
- [Orchestrator Docker-contention signature](project_orchestrator_docker_contention_signature.md) — 997 completed / 665 skipped + ConversationE2ETest ContainerLaunchException is contention, not a regression; re-run the suspect AFTER the control
- [Dev Services teardown false kill](project_dev_services_teardown_false_kill.md) — a ConnectException red with tests SKIPPED is Dev Services racing its own Postgres, not a mutation kill
