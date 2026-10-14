# Factory M4 slice 1 — verify: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** a held item build is checked by the operator-declared commands on a clean copy of its checkpoint
commit; the result is passed, failed or unverified; passed continues to delivery, and the other two stop at a
gate with Retry build / Stop.

**Architecture:**
- **Orchestrator.** It projects a `work_verify_effect` row when verify starts. A dispatcher sends `VerifyWork`
  on `cs.run-commands`.
- **Run worker.** It runs a *verify unit* against the kept build unit:
  - a publisher-image init (`spire-verify-prepare`) rebuilds the checkpoint from the handoff bundles into a
    new volume;
  - agent-image check containers run each command.

  It answers `RunWorkVerified` on the new topic `cs.run-verifications`.
- **Back in the orchestrator.** Passed completes the phase. Failed or unverified opens a result gate, whose
  approve rewinds to build. The retry starts from the checkpoint, with the failure in its prompt.

**Tech stack:** Java 25, Quarkus 3.38.3, SmallRye Kafka, PostgreSQL/Flyway, docker-java, JGit, React + vitest.

**Spec:** [`docs/superpowers/specs/2026-10-07-factory-m4-verify-design.md`](../specs/2026-10-07-factory-m4-verify-design.md)
(approved 2026-10-07). Read it before any task; section numbers below (§n) refer to it.

## Global Constraints

- **No local builds.** Never run `gradlew`, `npm test`, `npx vitest|tsc|eslint` on the host
  (`no-local-builds.md`). Every "verify" step is: commit, `git push`, then `gh pr checks <PR> --watch`. On
  red, `gh run view <run-id> --log-failed`.
- **Branch and PR.** Implementation runs on `feat/m4-verify`, cut from `docs/m4-verify-design`, so the spec
  and plan travel with the code. One draft PR, opened in Task 0.
- **Commits.**
  - Imperative subject of 72 characters or fewer, with a body for non-trivial changes.
  - Add `Part of #115`.
  - No closing keyword before an issue number.
  - No AI or vendor names, and no `Co-Authored-By` trailer.
- **Test data.** Every identifier in a test is `TEST-` prefixed. Hosts are `*.example.test`. No market
  figures.
- **Java style.**
  - 4-space indent. Explicit types are preferred over `var` in new code, but the surrounding dense style
    of `work/` (compact one-liners) is kept when editing those files.
  - Wire records: a new component is added once, through a wither or the full constructor, and every
    shorter constructor passes the default explicitly.
  - Domain code in `spire-contract` imports no framework (Jackson annotations only).
- **Money** in millicents. Host dev ports are in the 34xxx range.
- **Time limit.**
  - The verify time limit covers prepare plus checks, and lies in [60, `spire.factory.wall-clock-seconds`].
  - Default 1800.
  - The run worker refuses a value above `spire.run.max-wall-clock-seconds`, as
    `UNVERIFIED / verify_could_not_run`.
- **Commands.** At most 20, each 1–1000 chars, no line breaks. Order is kept.
- **Output tail.**
  - At most 200 lines per check, each line clipped to 2000 chars, at most 64 KiB per check.
  - It rides `RunWorkVerified`.
  - It is stored Tink-encrypted under AAD `work-verify-result:<attemptId>`.
- **Exit codes.** 126 and 127 → `tool_missing`. Any other non-zero → `check_failed`. A container that never
  started → `verify_could_not_run`.
- **Reasons (exact strings).**
  - Gate reasons: `verify_failed`, `verify_unverified`, `verify_stopped_by_operator`.
  - Sub-reasons: `check_failed`, `no_checks_declared`, `tool_missing`, `timed_out`, `checkpoint_missing`,
    `verify_could_not_run`.
- **Binding.** `WorkPreparation.VERIFY_BINDING = 5`.
- **Migrations.**
  - Orchestrator: `V87__verify.sql`.
  - Run worker: `V5__work_verify.sql`.
  - If either number is taken on master when you start, take the next free one and keep the name.

**Mutation checks (no local runs).** Each task lists its guard lines. For each one:
1. Commit a mutant that breaks that line on a scratch branch `mut/m4-verify-t<N>-<k>` off the task's commit.
2. Push it and open a draft PR against `feat/m4-verify` titled `TEST mutant t<N>-<k> (do not merge)`.
3. Read `gh pr checks`. Exactly one test should fail, and it must be the named test.
4. Close the PR and delete the branch.
5. Record the result in `.claude/reviews/global/factory-m4-verify-mutations.md`: the line, the mutant, the
   failing test and the run URL.

If CI budget is a concern, batch the mutants of one task into one branch, one commit per mutant, and read
each commit's run.

## Review Focus

These are inputs the spec implies but no task's main tests reach. Each line has its test added to the owning
task.

1. **A command with shell metacharacters** (`./gradlew check && echo ok`, quotes, `$VAR`) must run exactly
   as typed under `/bin/sh -c`. It must not be split or quoted twice. Test in Task 8:
   `aShellCommandRunsAsTyped`.
2. **A check that prints megabytes, or one line with no newline,** must not exhaust memory or break JSON.
   Test in Task 8: `anEndlessLineIsClippedAndTheTailStaysBounded`. Test in Task 1:
   `aTailOverTheCapIsRefused`.
3. **A retry after the kept unit is gone** (`checkpoint_missing`) must start from the base, not crash the
   build init. Test in Task 12: `aRetryAfterAMissingCheckpointStartsFromTheBase`.
4. **The operator saves an empty command list and then adds a blank line** — blank lines are dropped, not
   saved as a command that runs `sh -c ''` and passes. Test in Task 10: `blankLinesAreNotCommands`.
5. **A verify result arrives after the item was taken over or retired** — it is recorded, it opens no gate,
   and it does not throw in the consumer. Test in Task 11: `aResultForASuspendedItemOpensNoGate`.

---

### Task 0: Branch and draft PR

**Files:** none.

- [ ] **Step 1: Create the branch from the design branch**

```bash
git fetch origin
git switch -c feat/m4-verify docs/m4-verify-design
git push -u origin feat/m4-verify
```

- [ ] **Step 2: Open a draft PR**

```bash
gh pr create --draft --base master --head feat/m4-verify \
  --title "Verify held item builds with the operator's checks" \
  --body "M4 slice 1 (verify). Spec: docs/superpowers/specs/2026-10-07-factory-m4-verify-design.md. Plan: docs/superpowers/plans/2026-10-07-factory-m4-verify.md.

Part of #115"
```

Record the PR number. Every later "push and read CI" step uses it as `<PR>`.

---

### Task 1: Verification types, execution evidence and messages (`spire-contract`)

**Files:**
- Create: `spire-contract/src/main/java/dev/codespire/contract/work/WorkVerification.java`
- Create: `spire-contract/src/main/java/dev/codespire/contract/event/RunVerification.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/work/WorkExecution.java`
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/RunCommand.java` (add `VerifyWork`)
- Modify: `spire-contract/src/test/java/dev/codespire/contract/ContractSchemaSnapshotTest.java:59-75,153-155`
- Modify: `spire-contract/src/test/resources/contract-schema.txt`
- Test: `spire-contract/src/test/java/dev/codespire/contract/work/WorkVerificationTest.java` (new)
- Test: `spire-contract/src/test/java/dev/codespire/contract/work/WorkExecutionTest.java`

**Interfaces — Produces:**
```java
// dev.codespire.contract.work
public record WorkVerification(UUID attemptId, String head, Outcome outcome, String reason, List<CheckResult> checks) {
    public enum Outcome { PASSED, FAILED, UNVERIFIED }
    public record CheckResult(String command, Integer exitCode, long wallMillis, String outputTail) {}
    public static final int MAX_TAIL_CHARS = 64 * 1024;
    public boolean passed();
}
// WorkExecution gains component 7: WorkVerification verification (nullable)
public WorkExecution verified(WorkVerification verification);   // sets verificationAttempt AND verification
// dev.codespire.contract.command.RunCommand
record VerifyWork(String runId, WorkRunBinding work, UUID attemptId, String head,
                  List<String> commands, long timeoutSeconds) implements RunCommand
// dev.codespire.contract.event
public sealed interface RunVerification { String runId();
    record RunWorkVerified(String runId, WorkRunBinding work, WorkVerification verification) implements RunVerification {} }
```

- [ ] **Step 1: Write the failing tests**

`WorkVerificationTest.java`:
```java
package dev.codespire.contract.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorkVerificationTest {
    final UUID attempt=UUID.randomUUID();
    final String head="b".repeat(40);
    WorkVerification.CheckResult check(Integer exit){return new WorkVerification.CheckResult("TEST-check",exit,5,"TEST-tail");}

    @Test void passedNeedsEveryCheckToExitZeroAndNoReason(){
        assertTrue(new WorkVerification(attempt,head,WorkVerification.Outcome.PASSED,null,List.of(check(0),check(0))).passed());
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.PASSED,null,List.of(check(1))));
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.PASSED,null,List.of()));
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.PASSED,"check_failed",List.of(check(0))));
    }
    @Test void aResultThatIsNotPassedNamesAKnownReason(){
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.FAILED,null,List.of(check(1))));
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.UNVERIFIED,"TEST-unknown",List.of()));
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.FAILED,"tool_missing",List.of(check(127))));
        assertFalse(new WorkVerification(attempt,head,WorkVerification.Outcome.UNVERIFIED,"no_checks_declared",List.of()).passed());
    }
    @Test void aPartialHeadIsRefused(){
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,"abc",WorkVerification.Outcome.UNVERIFIED,"timed_out",List.of()));
    }
    @Test void aTailOverTheCapIsRefused(){
        String big="x".repeat(WorkVerification.MAX_TAIL_CHARS+1);
        assertThrows(IllegalArgumentException.class,()->new WorkVerification.CheckResult("TEST-check",1,1,big));
    }
    @Test void survivesAJsonRoundTrip() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var value=new WorkVerification(attempt,head,WorkVerification.Outcome.FAILED,"check_failed",List.of(check(0),check(2)));
        assertEquals(value,mapper.readValue(mapper.writeValueAsBytes(value),WorkVerification.class));
    }
}
```

Add to `WorkExecutionTest.java`:
```java
    @Test void verificationEvidenceIsCarriedBesideTheOldAttempt(){
        var result=new WorkVerification(UUID.randomUUID(),built.head(),WorkVerification.Outcome.PASSED,null,
                java.util.List.of(new WorkVerification.CheckResult("TEST-check",0,1,"")));
        var verified=built.verified(result);
        assertEquals(result.attemptId(),verified.verificationAttempt());assertEquals(result,verified.verification());
    }
    @Test void aVerificationOfAnotherHeadIsRefused(){
        var other=new WorkVerification(UUID.randomUUID(),"c".repeat(40),WorkVerification.Outcome.UNVERIFIED,"timed_out",java.util.List.of());
        assertThrows(IllegalArgumentException.class,()->built.verified(other));
    }
    @Test void anExecutionStoredBeforeVerificationExistedStillReads() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(built.verified(UUID.randomUUID()));
        json.remove("verification");
        var read=mapper.treeToValue(json,WorkExecution.class);
        assertNotNull(read.verificationAttempt());assertNull(read.verification());
    }
```

Replace the existing `phaseProofsPreserveTheBuildAndEarlierEvidence` expected value. Use the 7-argument
constructor with `null` for `verification`:
`new WorkExecution("TEST-run",binding,built.head(),verification,null,pr,"TEST-review")`. The `UUID`
overload `verified(UUID)` stays for stored-history tests only.

- [ ] **Step 2: Write `WorkVerification`**

```java
package dev.codespire.contract.work;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What a verify attempt found for one checkpoint (M4 verify, spec §3.2). PASSED is the only outcome that
 * may authorize delivery; UNVERIFIED says the checks could not run and must never render as passed.
 */
public record WorkVerification(UUID attemptId, String head, Outcome outcome, String reason, List<CheckResult> checks) {
    public enum Outcome { PASSED, FAILED, UNVERIFIED }

    /** Bounded so an agent-controlled log cannot grow an event, a row or a screen without limit. */
    public static final int MAX_TAIL_CHARS = 64 * 1024;
    public static final Set<String> UNVERIFIED_REASONS = Set.of("no_checks_declared", "tool_missing", "timed_out",
            "checkpoint_missing", "verify_could_not_run");

    /** @param exitCode null when the container never started */
    public record CheckResult(String command, Integer exitCode, long wallMillis, String outputTail) {
        public CheckResult {
            if (command == null || command.isBlank()) throw new IllegalArgumentException("A check names its command");
            if (wallMillis < 0) throw new IllegalArgumentException("A check cannot take negative time");
            outputTail = outputTail == null ? "" : outputTail;
            if (outputTail.length() > MAX_TAIL_CHARS) throw new IllegalArgumentException("A check tail is over its cap");
        }
    }

    public WorkVerification {
        Objects.requireNonNull(attemptId, "A verification names its attempt");
        Objects.requireNonNull(outcome, "A verification has an outcome");
        if (head == null || !head.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("A verification names its full head");
        checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        switch (outcome) {
            case PASSED -> {
                if (reason != null) throw new IllegalArgumentException("A passed verification has no reason");
                if (checks.isEmpty() || checks.stream().anyMatch(check -> !Integer.valueOf(0).equals(check.exitCode())))
                    throw new IllegalArgumentException("Passed means every declared check ran and exited 0");
            }
            case FAILED -> {
                if (!"check_failed".equals(reason)) throw new IllegalArgumentException("A failed verification is check_failed");
            }
            case UNVERIFIED -> {
                if (!UNVERIFIED_REASONS.contains(reason)) throw new IllegalArgumentException("Unknown unverified reason " + reason);
            }
        }
    }

    public boolean passed() { return outcome == Outcome.PASSED; }
}
```

- [ ] **Step 3: Extend `WorkExecution`**

Replace the record header and the withers with:
```java
public record WorkExecution(String runId,WorkRunBinding build,String head,UUID verificationAttempt,
                            WorkVerification verification,PullRequestRef pullRequest,String reviewId) {
    /** Every execution recorded before M4 carried an attempt id only. */
    public WorkExecution(String runId,WorkRunBinding build,String head,UUID verificationAttempt,PullRequestRef pullRequest,String reviewId) {
        this(runId,build,head,verificationAttempt,null,pullRequest,reviewId);
    }
    public WorkExecution {
        // (existing checks unchanged)
        if(verification!=null && (!verification.attemptId().equals(verificationAttempt) || !verification.head().equals(head)))
            throw new IllegalArgumentException("A verification must name this execution's attempt and head");
    }
    /** Stored history and TEST-only drivers before M4: an attempt with no outcome. Delivery refuses it. */
    public WorkExecution verified(UUID attempt) {return new WorkExecution(runId,build,head,Objects.requireNonNull(attempt),null,pullRequest,reviewId);}
    public WorkExecution verified(WorkVerification result) {
        Objects.requireNonNull(result);
        if(!result.head().equals(head))throw new IllegalArgumentException("A verification of another head cannot verify this build");
        return new WorkExecution(runId,build,head,result.attemptId(),result,pullRequest,reviewId);
    }
    public WorkExecution delivered(PullRequestRef ref) {return new WorkExecution(runId,build,head,verificationAttempt,verification,Objects.requireNonNull(ref),reviewId);}
    public WorkExecution reviewed(String id) {return new WorkExecution(runId,build,head,verificationAttempt,verification,pullRequest,Objects.requireNonNull(id));}
}
```

Leave the existing six-argument call sites alone; the overload keeps them valid. Then check every
`new WorkExecution(` in `src/main` with `git grep -n "new WorkExecution(" -- '*/src/main/*'`. Each site that
rebuilds an existing execution must use a wither instead of the six-argument form, or it drops
`verification`. Today the only such site is `WorkItemRunBridge.java:112-114`. It builds a fresh build
execution, so the six-argument form is correct there.

- [ ] **Step 4: Add `VerifyWork` to `RunCommand`**

Add `@JsonSubTypes.Type(value = RunCommand.VerifyWork.class, name = "VerifyWork")` and:
```java
    /**
     * Check a held build's checkpoint with the operator's commands (M4 verify). Rides the WORK topic: it
     * takes the worker's one execution slot like a build. It carries no credential: the worker reuses the
     * held build's own read credential, and the check containers receive none.
     */
    record VerifyWork(String runId,dev.codespire.contract.work.WorkRunBinding work,java.util.UUID attemptId,String head,
                      List<String> commands,long timeoutSeconds) implements RunCommand {
        public static final int MAX_COMMANDS=20,MAX_COMMAND_CHARS=1000;
        public VerifyWork {
            if(runId==null || runId.isBlank())throw new IllegalArgumentException("A verify names its held run");
            Objects.requireNonNull(work,"A verify binds the exact build");
            Objects.requireNonNull(attemptId,"A verify names its attempt");
            if(head==null || !head.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("A verify names its full head");
            commands=List.copyOf(Objects.requireNonNull(commands,"commands"));
            if(commands.size()>MAX_COMMANDS || commands.stream().anyMatch(c->c==null || c.isBlank() || c.length()>MAX_COMMAND_CHARS || c.indexOf('\n')>=0 || c.indexOf('\r')>=0))
                throw new IllegalArgumentException("Verify commands are 0-20 single lines of at most 1000 characters");
            if(timeoutSeconds<1)throw new IllegalArgumentException("A verify needs a time limit");
        }
    }
```

- [ ] **Step 5: Add `RunVerification`**

```java
package dev.codespire.contract.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.contract.work.WorkVerification;
import java.util.Objects;

/**
 * What a verify unit found (M4). Rides {@code cs.run-verifications}, keyed by runId, and NOT
 * {@code cs.run-results}: the item bridge stores any non-ready result there as the build's terminal payload,
 * so a verification on that topic would have replaced the later publication result (spec §4.1).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = RunVerification.RunWorkVerified.class, name = "RunWorkVerified")})
public sealed interface RunVerification {
    String runId();
    record RunWorkVerified(String runId, WorkRunBinding work, WorkVerification verification) implements RunVerification {
        public RunWorkVerified {
            if (runId == null || runId.isBlank()) throw new IllegalArgumentException("A verification names its run");
            Objects.requireNonNull(work, "work");
            Objects.requireNonNull(verification, "verification");
        }
    }
}
```

- [ ] **Step 6: Extend the schema snapshot**

- Add `RunVerification.class` to `ROOTS` and to `KAFKA_ROOTS`.
- Add `WorkVerification.class` and `WorkVerification.CheckResult.class` to the nested list at `:153`.
- Regenerate the golden by hand: the expected lines are the rendered records. Add, in this order:
  - the `RunWorkVerified(...)` line under a `# RunVerification` block;
  - the `VerifyWork(...)` line in the `# RunCommand` block, keeping the sorted order;
  - the new 7-component `WorkExecution` line;
  - the `# WorkVerification` and `# CheckResult` blocks.

  CI prints the actual rendering on mismatch. If it differs from what you wrote, copy CI's text only after
  checking that each component name and type is what this task declares.

- [ ] **Step 7: Commit, push, read CI**

```bash
git add spire-contract
git commit -m "Add verification evidence and the verify messages to the contract" -m "WorkVerification carries passed, failed or unverified with bounded check
tails. WorkExecution keeps its attempt id and gains the result, so stored
history still reads. VerifyWork and RunWorkVerified are the two new
messages.

Part of #115"
git push && gh pr checks <PR> --watch
```

Expected: `fast tests` green. Read `ContractSchemaSnapshotTest` in the run if it fails.

**Mutation guards:**
1. In `WorkVerification`, the `PASSED` branch's "every check exited 0". Expected to fail:
   `passedNeedsEveryCheckToExitZeroAndNoReason`.
2. `UNVERIFIED_REASONS.contains`. Expected to fail: `aResultThatIsNotPassedNamesAKnownReason`.
3. `WorkExecution`'s head check in `verified(WorkVerification)`. Expected to fail:
   `aVerificationOfAnotherHeadIsRefused`.

---

### Task 2: The verify commands join the preparation binding (`spire-contract`)

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/work/WorkPreparation.java`
- Test: `spire-contract/src/test/java/dev/codespire/contract/work/WorkPreparationBindingVersionTest.java`

**Interfaces — Produces:**
```java
public static final int VERIFY_BINDING = 5;
// record components appended: List<String> verifyCommands, long verifyTimeoutSeconds
public WorkPreparation(Artifact specification, Artifact plan, String baseBranch, String baseCommit,
    String harness, String model, String registeredBy, int bindingVersion, String effort, String payWith,
    List<String> verifyCommands, long verifyTimeoutSeconds)
```

- [ ] **Step 1: Write the failing tests** (add to `WorkPreparationBindingVersionTest`; reuse its existing
  `artifact()`/fixture helpers — read the top of the file first and use the same names)

```java
    @Test void version5HashesTheVerifyCommandsAndTheLimit(){
        var a=prepared(List.of("./gradlew check"),1800);var b=prepared(List.of("./gradlew test"),1800);var c=prepared(List.of("./gradlew check"),900);
        assertNotEquals(a.binding(),b.binding());assertNotEquals(a.binding(),c.binding());
        assertNotEquals(prepared(List.of("a","b"),60).binding(),prepared(List.of("b","a"),60).binding(),"order is part of what was approved");
    }
    @Test void olderVersionsKeepTheirHashAndCarryNoCommands(){
        var v4=new WorkPreparation(spec,plan,"main","a".repeat(40),"codex","TEST-model","TEST-operator",WorkPreparation.PAY_WITH_BINDING,null,null);
        assertEquals(List.of(),v4.verifyCommands());assertEquals(0,v4.verifyTimeoutSeconds());
        assertThrows(IllegalArgumentException.class,()->new WorkPreparation(spec,plan,"main","a".repeat(40),"codex","TEST-model","TEST-operator",
                WorkPreparation.PAY_WITH_BINDING,null,null,List.of("make"),60));
    }
    @Test void version5RefusesCommandsTheWorkerWouldRefuse(){
        assertThrows(IllegalArgumentException.class,()->prepared(List.of("a\nb"),60));
        assertThrows(IllegalArgumentException.class,()->prepared(List.of(" "),60));
        assertThrows(IllegalArgumentException.class,()->prepared(java.util.Collections.nCopies(21,"make"),60));
        assertThrows(IllegalArgumentException.class,()->prepared(List.of("make"),0));
    }
    @Test void anEmptyListIsAllowedAndVerifiesAsNoChecksDeclared(){assertEquals(List.of(),prepared(List.of(),1800).verifyCommands());}
    @Test void storedJsonWithoutVerifyFieldsReads() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var v4=new WorkPreparation(spec,plan,"main","a".repeat(40),"codex","TEST-model","TEST-operator",WorkPreparation.PAY_WITH_BINDING,null,null);
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(v4);json.remove("verifyCommands");json.remove("verifyTimeoutSeconds");
        assertEquals(v4.binding(),mapper.treeToValue(json,WorkPreparation.class).binding());
    }
    WorkPreparation prepared(List<String> commands,long timeout){
        return new WorkPreparation(spec,plan,"main","a".repeat(40),"codex","TEST-model","TEST-operator",WorkPreparation.VERIFY_BINDING,null,null,commands,timeout);
    }
```
(`spec`/`plan` are `STORED` artifacts as the file already builds for version ≥ 2.)

- [ ] **Step 2: Implement**

- **Constant:** add `VERIFY_BINDING = 5`, with Javadoc in the style of the others: *"Adds the verify
  commands and their time limit (M4). They decide what 'passed' means for this build, so a gate approves
  the exact list."*
- **Record:** append `List<String> verifyCommands, long verifyTimeoutSeconds` to the record.
- **Shorter constructors:**
  - Add a 10-argument constructor that delegates with `List.of(), 0`.
  - Change the existing shorter constructors to call the 10-argument one, unchanged.
- **Compact constructor:**

```java
        if (bindingVersion < TRACKER_BINDING || bindingVersion > VERIFY_BINDING)
            throw new IllegalArgumentException("Unknown preparation binding version " + bindingVersion);
        // ...existing effort/payWith checks...
        verifyCommands = verifyCommands == null ? List.of() : List.copyOf(verifyCommands);
        if (bindingVersion < VERIFY_BINDING && (!verifyCommands.isEmpty() || verifyTimeoutSeconds != 0))
            throw new IllegalArgumentException("Verify commands need binding version " + VERIFY_BINDING);
        if (bindingVersion >= VERIFY_BINDING) {
            if (verifyTimeoutSeconds < 1) throw new IllegalArgumentException("A version 5 preparation carries its verify time limit");
            if (verifyCommands.size() > 20 || verifyCommands.stream().anyMatch(c -> c == null || c.isBlank()
                    || c.length() > 1000 || c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0))
                throw new IllegalArgumentException("Verify commands are 0-20 single lines of at most 1000 characters");
        }
```

- **`binding()`:** after the `PAY_WITH_BINDING` line, add:

```java
        if (bindingVersion >= VERIFY_BINDING) {
            value.append(verifyCommands.size()).append('#');
            for (String command : verifyCommands) value.append(command.length()).append(':').append(command);
            String limit = Long.toString(verifyTimeoutSeconds);
            value.append(limit.length()).append(':').append(limit);
        }
```

- **Import:** add `import java.util.List;`. The record previously used fully-qualified `java.util.List`
  in `binding()`, and that still compiles.

- [ ] **Step 3: Push and read CI** (commit message `Bind the verify commands into a version 5 preparation`, body naming why: a gate approves the list).

**Mutation guards:**
1. The `bindingVersion < VERIFY_BINDING && !empty` refusal. Expected to fail:
   `olderVersionsKeepTheirHashAndCarryNoCommands`.
2. The command loop in `binding()`. Expected to fail: `version5HashesTheVerifyCommandsAndTheLimit`.

---

### Task 3: The decider opens a result gate (`spire-contract`)

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/work/WorkItemLifecycle.java`
- Test: `spire-contract/src/test/java/dev/codespire/contract/work/WorkPhaseDecisionTest.java`

**Interfaces — Produces:**
```java
/** Failed or unverified: a gate on verify that only a human answers. Never PHASE_FAILED. */
public static WorkItemEvent verifyResultGate(WorkItemEvent item, WorkItemEvent previous, long historySize,
                                             java.time.Instant now, UUID decisionId, WorkVerification result)
public static final java.util.Set<String> VERIFY_RESULT_REASONS = java.util.Set.of("verify_failed","verify_unverified");
```

- [ ] **Step 1: Write the failing tests.** Read `WorkPhaseDecisionTest`'s helpers first. Use its existing
  item builder at phase `verify`. The tests are:

```java
    @Test void aFailedVerificationOpensAGateInAutoMode(){
        var item=atVerify("auto");
        var result=new WorkVerification(UUID.randomUUID(),"b".repeat(40),WorkVerification.Outcome.FAILED,"check_failed",
                List.of(new WorkVerification.CheckResult("TEST-check",1,1,"")));
        var next=WorkItemLifecycle.verifyResultGate(item,item,4,Instant.EPOCH,UUID.randomUUID(),result);
        assertEquals("waiting_approval",next.workflowStatus());assertEquals("verify_failed",next.reason());
        assertEquals("GATE_OPENED",next.milestone());assertEquals("verify",next.gate().phase());assertEquals(5,next.gate().itemRevision());
        assertEquals(item.preparation().binding(),next.gate().artifact());
    }
    @Test void anUnverifiedResultOpensAGateWithItsOwnReason(){
        var item=atVerify("approve");
        var result=new WorkVerification(UUID.randomUUID(),"b".repeat(40),WorkVerification.Outcome.UNVERIFIED,"tool_missing",List.of());
        assertEquals("verify_unverified",WorkItemLifecycle.verifyResultGate(item,item,4,Instant.EPOCH,UUID.randomUUID(),result).reason());
    }
    @Test void aPassedResultCannotOpenAResultGate(){
        var item=atVerify("auto");
        var passed=new WorkVerification(UUID.randomUUID(),"b".repeat(40),WorkVerification.Outcome.PASSED,null,
                List.of(new WorkVerification.CheckResult("TEST-check",0,1,"")));
        assertThrows(IllegalArgumentException.class,()->WorkItemLifecycle.verifyResultGate(item,item,4,Instant.EPOCH,UUID.randomUUID(),passed));
    }
    @Test void verifyOffOpensNoResultGate(){
        var item=atVerify("off");
        var result=new WorkVerification(UUID.randomUUID(),"b".repeat(40),WorkVerification.Outcome.UNVERIFIED,"timed_out",List.of());
        assertThrows(IllegalStateException.class,()->WorkItemLifecycle.verifyResultGate(item,item,4,Instant.EPOCH,UUID.randomUUID(),result));
    }
```

- [ ] **Step 2: Implement** in `WorkItemLifecycle`:

```java
    public static final java.util.Set<String> VERIFY_RESULT_REASONS=java.util.Set.of("verify_failed","verify_unverified");

    /**
     * Failed or unverified checks open a gate in every verify mode but off, auto included (spec §4.2, ADR-046):
     * only a human accepts a result that is not passed. It is not PHASE_FAILED, because verify fails a step,
     * never a work item (FR-F20).
     */
    public static WorkItemEvent verifyResultGate(WorkItemEvent item,WorkItemEvent previous,long historySize,java.time.Instant now,UUID decisionId,WorkVerification result) {
        if(result.passed())throw new IllegalArgumentException("A passed verification completes the phase");
        if(!"verify".equals(item.phase()))throw new IllegalStateException("A result gate belongs to verify");
        if("off".equals(item.policy().effective().getOrDefault(WorkPolicy.Phase.VERIFY,"off")))throw new IllegalStateException("Verify is off");
        long eventRevision=historySize+1+(clampChanged(previous,item)?1:0);
        WorkGate gate=new WorkGate(decisionId,1,"OPEN","verify",item.generation(),eventRevision,item.policyRevision(),item.authority(),WorkGate.artifactOf(item),
                now,now.plusSeconds(item.policy().limits().gateTtlSeconds()),null,null,null,null);
        String reason=result.outcome()==WorkVerification.Outcome.FAILED?"verify_failed":"verify_unverified";
        return state(item,"waiting_approval",reason,"GATE_OPENED",gate,item.progress().reserve(true));
    }
```

- [ ] **Step 3: Push and read CI.** Commit message: `Open a result gate when verification does not pass`.

**Mutation guards:**
1. `if(result.passed()) throw`. Expected to fail: `aPassedResultCannotOpenAResultGate`.
2. The `off` refusal. Expected to fail: `verifyOffOpensNoResultGate`.
3. The reason choice. Expected to fail: `anUnverifiedResultOpensAGateWithItsOwnReason`.

---

### Task 4: A build can start from a checkpoint (`spire-contract`)

**Files:**
- Modify: `spire-contract/src/main/java/dev/codespire/contract/command/RunCommand.java` (`ExecuteRun`)
- Test: `spire-contract/src/test/java/dev/codespire/contract/command/` (add `ExecuteRunCheckpointTest.java`)

**Interfaces — Produces:** `ExecuteRun` gains two components, `String startFromRunId` and
`String startFromHead`, after `harnessSignIn`. It also gains a wither:
`ExecuteRun fromCheckpoint(String runId, String head)`.

- [ ] **Step 1: Failing test**

```java
package dev.codespire.contract.command;

import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExecuteRunCheckpointTest {
    RunCommand.ExecuteRun run(){return new RunCommand.ExecuteRun("TEST-run",new RepoRef("TEST-ws","TEST-repo"),"https://forge.example.test/TEST.git",
            "main","a".repeat(40),"spire/work-TEST","TEST prompt","codex","TEST-model","TEST-image",List.of(),60,"TEST-scm","TEST-harness");}
    @Test void everyWitherKeepsTheCheckpoint(){
        var from=run().fromCheckpoint("TEST-previous","b".repeat(40));
        assertEquals("TEST-previous",from.atEffort("high").paidBySignIn().startFromRunId());
        assertEquals("b".repeat(40),from.atEffort("high").startFromHead());
    }
    @Test void aCheckpointNeedsBothItsRunAndAFullHead(){
        assertThrows(IllegalArgumentException.class,()->run().fromCheckpoint("TEST-previous","abc"));
        assertThrows(IllegalArgumentException.class,()->run().fromCheckpoint(" ","b".repeat(40)));
    }
    @Test void aRunWithoutACheckpointStartsFromItsBase(){assertNull(run().startFromRunId());assertNull(run().startFromHead());}
}
```
Read `RepoRef`'s constructor first and adjust the two `TEST-` arguments to match it.

- [ ] **Step 2: Implement.** Append the two components to the canonical constructor. Then:
  - Make every shorter constructor and every wither (`onExistingBranch`, `atEffort`, `paidBySignIn`) pass
    `startFromRunId, startFromHead` through. The shorter constructors pass `null, null`.
  - Add the new wither:
```java
        /** The same run, starting from another held run's checkpoint instead of the base (M4 retry build). */
        public ExecuteRun fromCheckpoint(String previousRunId, String head) {
            return new ExecuteRun(runId, repo, remoteUri, baseBranch, baseCommit, branch, prompt, harness, model,
                    agentImage, protectedPaths, maxWallClockSeconds, scmCredential, harnessCredential,
                    existingBranch, protectedBranch, reasoningEffort, harnessSignIn, previousRunId, head);
        }
```
  - In the compact constructor:
```java
            if ((startFromRunId == null) != (startFromHead == null)
                    || startFromRunId != null && (startFromRunId.isBlank() || !startFromHead.matches("[0-9a-f]{40}")))
                throw new IllegalArgumentException("A checkpoint start names its held run and its full head");
```
  - Add `startFromRunId` to `toString`.

- [ ] **Step 3: Update the golden line for `ExecuteRun`** in `contract-schema.txt`. Then push and read CI.
  Commit message: `Let a build start from a held run's checkpoint`.

**Mutation guard:** pass `null` for `startFromRunId` inside `atEffort`. Expected to fail:
`everyWitherKeepsTheCheckpoint`.

---

### Task 5: Import a checkpoint from handoff bundles (`spire-workspace`)

**Files:**
- Create: `spire-workspace/src/main/java/dev/codespire/workspace/CheckpointBundles.java`
- Create: `spire-workspace/src/main/java/dev/codespire/workspace/CheckpointMissingException.java`
- Modify: `spire-workspace/src/main/java/dev/codespire/workspace/PublishRepo.java`. Extract
  `copyOutOfReach` (`:139`) and `soleRefOf` (`:194`) into package-static helpers
  `static Path copyOutOfReach(Path bundle, Path store, long maxBytes)` and `static String soleRefOf(Path copy)`.
  The instance methods delegate to them with `privateStore`.
- Test: `spire-workspace/src/test/java/dev/codespire/workspace/CheckpointBundlesTest.java`

**Interfaces — Produces:**
```java
public final class CheckpointBundles {
    /** Fetch handoff bundles newest first until {@code head} resolves, then hard-reset the checked-out branch to it. */
    public static void importInto(Path workspace, Path handoff, String head, long maxBytes) throws IOException, GitAPIException;
}
public final class CheckpointMissingException extends IOException { public CheckpointMissingException(String head) }
```

- [ ] **Step 1: Failing tests.** Copy the `Fixture` approach of `PublishRepoTest`: a real origin, a work
  clone, and `git bundle create` via `ProcessBuilder`. Read that file's helpers and reuse them.

```java
class CheckpointBundlesTest {
    @TempDir Path dir;
    @AfterEach void release(){PublishRepo.releaseAllPackWindows();}

    @Test void theWorkspaceEndsExactlyAtTheCheckpointHead() throws Exception {
        var f=fixture();Path b1=f.bundle("one.txt","TEST one");String head=f.head();
        Path ws=f.cloneBase("verify");
        CheckpointBundles.importInto(ws,f.handoff,head,1<<20);
        assertEquals(head,git(ws,"rev-parse","HEAD"));assertTrue(Files.exists(ws.resolve("one.txt")));
        assertEquals("",git(ws,"status","--porcelain"));
    }
    @Test void aHeadAbsentFromTheNewestBundleResolvesFromAnOlderOne() throws Exception {
        var f=fixture();f.bundle("one.txt","TEST one");String first=f.head();
        f.rewriteHistory("two.txt","TEST rewritten");   // reset --hard base, commit, bundle as 2.bundle
        Path ws=f.cloneBase("verify");
        CheckpointBundles.importInto(ws,f.handoff,first,1<<20);
        assertEquals(first,git(ws,"rev-parse","HEAD"));
    }
    @Test void anUnknownHeadIsCheckpointMissing() throws Exception {
        var f=fixture();f.bundle("one.txt","TEST one");Path ws=f.cloneBase("verify");
        assertThrows(CheckpointMissingException.class,()->CheckpointBundles.importInto(ws,f.handoff,"c".repeat(40),1<<20));
    }
    @Test void aSymlinkInTheHandoffIsNotFollowed() throws Exception {
        var f=fixture();Path outside=Files.writeString(dir.resolve("outside.bundle"),"TEST");
        Files.createSymbolicLink(f.handoff.resolve("9.bundle"),outside);   // skip with assumeTrue on Windows privilege error
        Path ws=f.cloneBase("verify");
        assertThrows(CheckpointMissingException.class,()->CheckpointBundles.importInto(ws,f.handoff,"c".repeat(40),1<<20));
    }
}
```
`fixture().cloneBase(name)` calls
`WorkspaceClone.populate(f.originUri, f.base, name, ws, f.credential, "TEST", "TEST@factory.invalid")`.
It mirrors what the publisher init does.

- [ ] **Step 2: Implement `CheckpointBundles`**

```java
package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Rebuilds a held build's checkpoint in a fresh clone from the agent's handoff bundles (M4 verify, retry build).
 *
 * <p>The bundles are agent-written: each is copied out of reach without following links (the publisher's own
 * path) before git reads it, and nothing here runs a hook, a filter or the agent's config — the repository is
 * a fresh clone whose remote is already gone. Each bundle is BASE..HEAD at its moment, and the gated head may
 * be absent from the newest one if the agent rewrote history, so they are read newest first until it resolves.
 */
public final class CheckpointBundles {
    private static final String IMPORT_REF = "refs/spire/checkpoint-import";
    private CheckpointBundles() {}

    public static void importInto(Path workspace, Path handoff, String head, long maxBytes) throws IOException, GitAPIException {
        if (head == null || !head.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("A full checkpoint head is required");
        ObjectId wanted = ObjectId.fromString(head);
        Path store = Files.createTempDirectory("spire-checkpoint-");
        try (Git git = Git.open(workspace.toFile())) {
            for (Path bundle : newestFirst(handoff)) {
                if (git.getRepository().getObjectDatabase().has(wanted)) break;
                try {
                    Path copy = PublishRepo.copyOutOfReach(bundle, store, maxBytes);
                    String ref = PublishRepo.soleRefOf(copy);
                    git.fetch().setRemote(copy.toAbsolutePath().toString()).setRefSpecs(new RefSpec("+" + ref + ":" + IMPORT_REF)).call();
                } catch (IOException | GitAPIException unreadable) {
                    // One unreadable bundle is not the end: an older one may still hold the head.
                }
            }
            if (!git.getRepository().getObjectDatabase().has(wanted)) throw new CheckpointMissingException(head);
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(head).call();
            var update = git.getRepository().updateRef(IMPORT_REF);update.setForceUpdate(true);update.delete();
        } finally {
            try (Stream<Path> files = Files.walk(store)) { files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }

    /** Numeric sequence descending, as HandoffWatcher orders them ascending. */
    static List<Path> newestFirst(Path handoff) throws IOException {
        if (!Files.isDirectory(handoff, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> entries = Files.list(handoff)) {
            return entries.filter(p -> p.getFileName().toString().matches("[0-9]+\\.bundle"))
                    .sorted(Comparator.comparingLong((Path p) -> Long.parseLong(p.getFileName().toString().replace(".bundle", ""))).reversed())
                    .toList();
        }
    }
}
```
`CheckpointMissingException(String head)` uses the message
`super("checkpoint " + head + " is in no readable bundle")`. A symlinked bundle fails inside
`copyOutOfReach` (it is not a regular file) and is skipped.

- [ ] **Step 3: Push and read CI.** Commit message: `Rebuild a checkpoint from handoff bundles, newest first`.

**Mutation guards:**
1. `.reversed()` in `newestFirst`. Expected to fail: `aHeadAbsentFromTheNewestBundleResolvesFromAnOlderOne`,
   provided the newest bundle lacks the head. The fixture must make that true.
2. The final `has(wanted)` check. Expected to fail: `anUnknownHeadIsCheckpointMissing`.

---

### Task 6: Publisher modes: `spire-verify-prepare`, and `spire-clone` from a checkpoint (`spire-publisher`)

**Files:**
- Create: `spire-publisher/src/main/java/dev/codespire/publisher/VerifyPrepareMain.java`
- Create: `spire-publisher/docker/spire-verify-prepare.sh`
- Modify: `spire-publisher/Dockerfile` (add `COPY --chmod=755 docker/spire-verify-prepare.sh /usr/local/bin/spire-verify-prepare`)
- Modify: `spire-publisher/.dockerignore` (admit `docker/spire-verify-prepare.sh`)
- Modify: `spire-publisher/src/main/java/dev/codespire/publisher/CloneMain.java`
- Modify: `spire-publisher/src/main/java/dev/codespire/publisher/OutcomeWriter.java` (add `prepared(String head)`)
- Test: `spire-publisher/src/test/java/dev/codespire/publisher/VerifyPrepareTest.java`, `OutcomeWriterTest.java`

**Interfaces — Produces:**
- **Exit codes:** 0 prepared, 3 checkpoint missing, 1 any other failure, 2 misconfigured.
- **stdout:** `{"event":"prepared","head":"<sha>"}`, or `{"event":"failed","cause":"CHECKPOINT_MISSING"|"VERIFY_PREPARE_FAILED"|"PUBLISHER_MISCONFIGURED","detail":...}`.
- **Environment:**
  - `spire-verify-prepare` reads `SPIRE_REMOTE_URI`, `SPIRE_BASE_COMMIT`, `SPIRE_CLONE_USERNAME`,
    `SPIRE_CLONE_SECRET`, `SPIRE_CHECKPOINT_HEAD`, `SPIRE_HANDOFF_DIR`, `SPIRE_WORKSPACE_DIR` and
    `SPIRE_BUNDLE_MAX_BYTES`.
  - `spire-clone` additionally honours `SPIRE_CHECKPOINT_HEAD` plus `SPIRE_CHECKPOINT_DIR`. When both
    are set, it imports after `populate`. A missing checkpoint exits 3 with `CHECKPOINT_MISSING`.

- [ ] **Step 1: Failing tests.** `VerifyPrepareTest` calls a static
  `VerifyPrepareMain.run(Map<String,String> env, PrintStream out)` that returns the exit code. It does not
  call `System.exit`, so the method can be tested. Fixture as in Task 5.

```java
    @Test void preparesTheCheckpointAndReportsItsHead() throws Exception {
        var f=fixture();f.bundle("one.txt","TEST one");String head=f.head();Path ws=dir.resolve("ws");
        int exit=VerifyPrepareMain.run(env(f,head,ws),out);
        assertEquals(0,exit);assertTrue(output().contains("\"event\":\"prepared\",\"head\":\""+head+"\""));
        assertEquals(head,git(ws,"rev-parse","HEAD"));assertEquals("",git(ws,"remote"));
    }
    @Test void aMissingCheckpointExitsThree() throws Exception {
        var f=fixture();f.bundle("one.txt","TEST one");
        assertEquals(3,VerifyPrepareMain.run(env(f,"c".repeat(40),dir.resolve("ws")),out));
        assertTrue(output().contains("CHECKPOINT_MISSING"));
    }
    @Test void theCloneSecretNeverReachesTheOutput() throws Exception {
        var f=fixture();VerifyPrepareMain.run(env(f,"c".repeat(40),dir.resolve("ws")),out);
        assertFalse(output().contains(f.secret));
    }
```

- [ ] **Step 2: Implement `VerifyPrepareMain`**

```java
package dev.codespire.publisher;

import dev.codespire.workspace.*;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * Builds the clean verify workspace: a fresh clone of the base, the remote removed, then exactly the
 * checkpoint commit from the kept run's bundles (spec §3.1). Runs in the trusted publisher image, holds the
 * read credential, and executes nothing the agent wrote.
 */
public final class VerifyPrepareMain {
    static final int CHECKPOINT_MISSING = 3;
    public static void main(String[] args) { System.exit(run(System.getenv(), System.out)); }

    static int run(Map<String, String> env, PrintStream stdout) {
        OutcomeWriter outcome = new OutcomeWriter(stdout);
        String remote, base, head, username, secret; Path workspace, handoff; long maxBytes;
        try {
            CorporateTransport.apply(env);
            remote = RemoteUri.validated(Env.required(env, "SPIRE_REMOTE_URI"));
            base = Env.required(env, "SPIRE_BASE_COMMIT");
            head = Env.required(env, "SPIRE_CHECKPOINT_HEAD");
            username = Env.required(env, "SPIRE_CLONE_USERNAME");
            secret = Env.required(env, "SPIRE_CLONE_SECRET");
            workspace = Path.of(env.getOrDefault("SPIRE_WORKSPACE_DIR", "/workspace"));
            handoff = Path.of(Env.required(env, "SPIRE_HANDOFF_DIR"));
            maxBytes = Long.parseLong(Env.required(env, "SPIRE_BUNDLE_MAX_BYTES"));
        } catch (RuntimeException misconfigured) {
            outcome.failed("PUBLISHER_MISCONFIGURED", misconfigured.getMessage());
            return 2;
        }
        outcome = new OutcomeWriter(stdout, username, secret);
        try {
            WorkspaceClone.populate(remote, base, "spire-verify", workspace, new GitCredential(username, secret),
                    "spire-verify", "spire-verify@" + CloneMain.IDENTITY_DOMAIN);
            CheckpointBundles.importInto(workspace, handoff, head, maxBytes);
            outcome.prepared(head);
            return 0;
        } catch (CheckpointMissingException missing) {
            outcome.failed("CHECKPOINT_MISSING", missing.getMessage());
            return CHECKPOINT_MISSING;
        } catch (Exception failed) {
            outcome.failed("VERIFY_PREPARE_FAILED", failed.getClass().getSimpleName() + ": " + failed.getMessage());
            return 1;
        }
    }
}
```
- If `CloneMain.IDENTITY_DOMAIN` is private, make it package-private.
- `OutcomeWriter.prepared(head)` writes `{"event":"prepared","head":head}` through the same JSON path that
  `checkpoint` uses.
- `spire-verify-prepare.sh` is
  `exec java -cp '/opt/spire-publisher/lib/*' dev.codespire.publisher.VerifyPrepareMain "$@"`, with the same
  header as `spire-clone.sh`.

- [ ] **Step 3: `CloneMain` checkpoint import.** After `WorkspaceClone.populate(...)`:

```java
        String checkpoint = env.get("SPIRE_CHECKPOINT_HEAD");
        if (checkpoint != null && !checkpoint.isBlank()) {
            try {
                CheckpointBundles.importInto(workspace, Path.of(Env.required(env, "SPIRE_CHECKPOINT_DIR")), checkpoint,
                        Long.parseLong(env.getOrDefault("SPIRE_BUNDLE_MAX_BYTES", Long.toString(256L * 1024 * 1024))));
            } catch (CheckpointMissingException missing) {
                outcome.failed("CHECKPOINT_MISSING", missing.getMessage());
                System.exit(3);
            }
        }
```
Add a test in the existing `CloneMain` test, or a new `CloneFromCheckpointTest` that calls a refactored
`CloneMain.run(env, out)`. Refactor `main` the same way as `VerifyPrepareMain`. The test asserts that HEAD
is the checkpoint, and that the branch named `SPIRE_BRANCH` points at it.

- [ ] **Step 4: Push and read CI.** Commit message:
  `Add the verify-prepare publisher mode and clone from a checkpoint`.

**Mutation guards:**
1. `return CHECKPOINT_MISSING` changed to `return 1`. Expected to fail: `aMissingCheckpointExitsThree`.
2. The `OutcomeWriter(stdout, username, secret)` rebinding removed. Expected to fail:
   `theCloneSecretNeverReachesTheOutput`. The detail must contain the secret for this to bite, so make the
   remote URL embed it in that test.

---

### Task 7: Runtime SPI for verify units (`spire-runtime`)

**Files:**
- Modify: `spire-runtime/src/main/java/dev/codespire/runtime/ContainerSpec.java` (add `List<String> entrypoint`, nullable)
- Modify: `spire-runtime/src/main/java/dev/codespire/runtime/Mount.java` (add `String run`, nullable: a read-only mount of ANOTHER run's volume)
- Create: `spire-runtime/src/main/java/dev/codespire/runtime/VerifyUnitSpec.java`
- Create: `spire-runtime/src/main/java/dev/codespire/runtime/VerifyRun.java`
- Modify: `spire-runtime/src/main/java/dev/codespire/runtime/PublicationRuntime.java`
- Modify (fakes): `spire-run-worker/src/test/java/dev/codespire/runworker/HeldRunLauncherTest.java:21`, `WorkRunWorkerTest.java:62`
- Test: `spire-runtime/src/test/java/dev/codespire/runtime/VerifyUnitSpecTest.java`, `RuntimeSpiTest.java`

**Interfaces — Produces:**
```java
public record ContainerSpec(String image, List<String> argv, Map<String,String> environment, List<Mount> mounts, List<String> entrypoint)
    // 4-arg constructor kept: entrypoint null = the image's own
public record Mount(String volume, String path, boolean readOnly, String run)
    public static Mount ofRun(String run, String volume, String path)   // always read-only
public record VerifyUnitSpec(String runId, UUID attemptId, ContainerSpec prepare, List<ContainerSpec> checks,
                             EnterpriseEnvironment enterprise, long memoryBytes, long nanoCpus, long diskBytes, Duration timeout)
    public static final String VOLUME_PREFIX = "verify-";   // the verify volume's logical name is VOLUME_PREFIX + attemptId
public record VerifyRun(String preparedHead, Prepare prepare, List<Check> checks, boolean timedOut) {
    public enum Prepare { PREPARED, CHECKPOINT_MISSING, FAILED }
    public record Check(Integer exitCode, long wallMillis, List<String> tail) {}   // exitCode null = never started
}
// PublicationRuntime
VerifyRun verifyHeld(RunHandle handle, PublicationKey binding, VerifyUnitSpec spec, java.util.function.BooleanSupplier mayContinue);
void removeVerify(RunHandle handle, UUID attemptId);
```

- [ ] **Step 1: Failing tests** (`VerifyUnitSpecTest`)

```java
    @Test void checksMayNotSeeTheHandoff(){
        var check=new ContainerSpec("TEST-agent",List.of("make"),Map.of(),List.of(Mount.writable("verify-"+attempt,"/workspace"),Mount.readOnly("handoff","/handoff")),List.of("/bin/sh","-c"));
        assertThrows(IllegalArgumentException.class,()->spec(prepare(),List.of(check)));
    }
    @Test void checksWriteOnlyTheVerifyVolume(){
        var check=new ContainerSpec("TEST-agent",List.of("make"),Map.of(),List.of(Mount.writable("workspace","/workspace")),List.of("/bin/sh","-c"));
        assertThrows(IllegalArgumentException.class,()->spec(prepare(),List.of(check)));
    }
    @Test void aCheckCarriesNoCredentialVariable(){
        var check=new ContainerSpec("TEST-agent",List.of("make"),Map.of("SPIRE_CLONE_SECRET","TEST"),List.of(Mount.writable("verify-"+attempt,"/workspace")),List.of("/bin/sh","-c"));
        assertThrows(IllegalArgumentException.class,()->spec(prepare(),List.of(check)));
    }
    @Test void aForeignRunMountIsReadOnly(){
        assertThrows(IllegalArgumentException.class,()->new Mount("handoff","/checkpoint",false,"TEST-previous"));
        assertTrue(Mount.ofRun("TEST-previous","handoff","/checkpoint").readOnly());
    }
```
The rule behind the third test: a check's environment may hold only names the spec copies from the
enterprise environment. Any name matching `(?i).*(SECRET|TOKEN|KEY|PASSWORD|CREDENTIAL).*`, or starting
with `SPIRE_`, is refused. That is the "no model key, no run secrets" guarantee, checked where the unit is
built.

- [ ] **Step 2: Implement the records**

- **`ContainerSpec`:** add the component. The 4-argument constructor delegates with `null`. When
  `entrypoint` is non-null, copy it and refuse an empty list. Add `entrypoint` to `toString`.
- **`Mount`:** add `run`. The existing 3-argument factories delegate with `null`. The compact constructor
  refuses a non-null `run` that is blank or not `readOnly`.
- **`VerifyUnitSpec` compact constructor** requires all of:
  - non-null `runId` and `attemptId`, a positive timeout, and positive resource limits;
  - between 0 and 20 checks;
  - `prepare` mounts exactly `handoff` read-only plus the verify volume writable;
  - every check mounts only the verify volume, writable at `/workspace`;
  - every check has an entrypoint;
  - every check's environment passes the credential-name rule above.
- **`VerifyRun`:** copies its lists.

- [ ] **Step 3: Extend `PublicationRuntime`** with the two methods and Javadoc saying:
  - verify reads the kept unit and changes nothing in it;
  - every verify resource carries the run id, the hold label, role `verify` and the attempt id;
  - `removeVerify` is idempotent.

  Implement both methods in the two test fakes:
  - `verifyHeld` returns a value the test sets: a field `VerifyRun nextVerify`.
  - `removeVerify` records the attempt id in a list.

- [ ] **Step 4: Push and read CI.** Commit message: `Describe verify units in the runtime SPI`.

**Mutation guards:**
1. The handoff-in-check refusal. Expected to fail: `checksMayNotSeeTheHandoff`.
2. The credential-name rule. Expected to fail: `aCheckCarriesNoCredentialVariable`.

---

### Task 8: Docker runs verify units (`spire-runtime-docker`)

**Files:**
- Modify: `spire-runtime-docker/src/main/java/dev/codespire/runtime/docker/DockerRunRuntime.java`
- Test: `spire-runtime-docker/src/test/java/dev/codespire/runtime/docker/DockerVerifyIT.java` (new; real daemon, alpine as in `DockerPublicationIT`)

**Interfaces — Consumes:** Task 7. **Produces:** the Docker implementation, plus these constants:
- `static final String VERIFY = "verify"` (role);
- `static final String VERIFY_ATTEMPT_LABEL = "dev.codespire.verifyAttempt"`;
- `static final int VERIFY_TAIL_LINES = 200, VERIFY_LINE_CHARS = 2000, VERIFY_TAIL_CHARS = 64 * 1024`.

- [ ] **Step 1: Failing ITs**

Model them on `DockerPublicationIT`: the `unit()` helper, `KEY`, and cleanup. Each test creates a held unit
whose agent writes a file into `/handoff`. A real git bundle is not needed here: the `prepare` container is an
alpine `sh -c` script standing in for the publisher mode. It echoes
`{"event":"prepared","head":"<40 hex>"}` and copies `/handoff/src/.` into `/workspace`.

```java
    @Test void checksRunInOrderOnTheVerifyVolumeAndTheFirstFailureStops() {
        RunHandle held=held("echo TEST > /handoff/marker");
        VerifyRun run=runtime.verifyHeld(held,KEY,verify(held,"true","exit 4","echo never"),()->true);
        assertEquals(VerifyRun.Prepare.PREPARED,run.prepare());
        assertEquals(List.of(0,4),run.checks().stream().map(VerifyRun.Check::exitCode).toList());
        assertFalse(run.timedOut());
    }
    @Test void aShellCommandRunsAsTyped() {
        RunHandle held=held("true");
        VerifyRun run=runtime.verifyHeld(held,KEY,verify(held,"A='TEST a b'; [ \"$A\" = 'TEST a b' ] && echo ok-$((1+1))"),()->true);
        assertEquals(0,run.checks().getFirst().exitCode());assertTrue(run.checks().getFirst().tail().contains("ok-2"));
    }
    @Test void anEndlessLineIsClippedAndTheTailStaysBounded() {
        RunHandle held=held("true");
        VerifyRun run=runtime.verifyHeld(held,KEY,verify(held,"seq 1 5000; head -c 300000 /dev/zero | tr '\\0' x"),()->true);
        var tail=run.checks().getFirst().tail();
        assertTrue(tail.size()<=DockerRunRuntime.VERIFY_TAIL_LINES);
        assertTrue(tail.stream().allMatch(l->l.length()<=DockerRunRuntime.VERIFY_LINE_CHARS+64));
        assertTrue(String.join("\n",tail).length()<=DockerRunRuntime.VERIFY_TAIL_CHARS);
    }
    @Test void theTimeLimitKillsAndReportsTimedOut() {
        RunHandle held=held("true");
        VerifyRun run=runtime.verifyHeld(held,KEY,verifyWithin(held,Duration.ofSeconds(3),"sleep 60"),()->true);
        assertTrue(run.timedOut());
    }
    @Test void aCheckSeesNoHandoffAndNoUncommittedAgentFile() {
        RunHandle held=held("echo TEST-stray > /workspace/stray; echo TEST > /handoff/marker");
        VerifyRun run=runtime.verifyHeld(held,KEY,verify(held,"[ ! -e /handoff/marker ] && [ ! -e /workspace/stray ]"),()->true);
        assertEquals(0,run.checks().getFirst().exitCode());
    }
    @Test void verifyResourcesKeepTheUnitHeldAndAreNeverItsHandle() {
        RunHandle held=held("true");
        runtime.verifyHeld(held,KEY,verify(held,"sleep 1"),()->true);
        assertTrue(runtime.publicationHeld(held));
        assertTrue(runtime.discoverUnits().stream().filter(h->h.runId().equals(held.runId()))
                .allMatch(h->!role(h.providerRunId()).equals("verify")));
        runtime.removeVerify(held,attempt);runtime.removeVerify(held,attempt);   // idempotent
        assertEquals(0,verifyResources(held.runId()).size());
        runtime.destroyHeld(held,KEY);
    }
    @Test void cancelStopsARunningCheck() throws Exception {
        RunHandle held=held("true");
        var running=java.util.concurrent.CompletableFuture.supplyAsync(()->runtime.verifyHeld(held,KEY,verify(held,"sleep 60"),()->true));
        awaitRunning(held.runId(),"verify");runtime.cancel(held);
        assertNotEquals(Integer.valueOf(0),running.get(30,java.util.concurrent.TimeUnit.SECONDS).checks().getFirst().exitCode());
    }
```
`verify(held, String... commands)` builds a `VerifyUnitSpec` from these parts:
- `prepare`: alpine, `sh -c` with the stand-in script, mounting handoff read-only and the verify volume
  writable.
- the checks: alpine, entrypoint `["/bin/sh","-c"]`, argv `[command]`, verify volume at `/workspace`.
- the timeout: 60 s.

`role(id)` inspects the container's role label. `verifyResources` lists containers and volumes with
`VERIFY_ATTEMPT_LABEL=attempt`.

- [ ] **Step 2: Implement in `DockerRunRuntime`**

1. **`createContainer`:** generalise it to take
   `(String runId, EnterpriseEnvironment enterprise, HostConfig host, ContainerSpec container, Map<String,String> labels, String name)`.
   The existing `createContainer(spec, ...)` builds its arguments and delegates. In the new form, call
   `create.withEntrypoint(container.entrypoint())` when the entrypoint is non-null.
2. **`bindsFor`:** use `volumeName(mount.run() != null ? mount.run() : runId, mount.volume())`. In
   `volumeNamesOf`, skip mounts whose `run()` is non-null, so a foreign volume is never created. Before
   `create`, check that every foreign volume exists (`inspectVolumeCmd`). If one is missing, throw
   `IllegalStateException("checkpoint volume is gone")`.
3. **`discoverUnits`:** skip containers whose `ROLE_LABEL` is `verify`.
4. **`cancel`:** after the delivery loop, also kill containers whose role is `verify`.
5. **`verifyHeld(handle, binding, spec, mayContinue)`:**

```java
    @Override
    public VerifyRun verifyHeld(RunHandle handle, PublicationKey binding, VerifyUnitSpec spec, java.util.function.BooleanSupplier mayContinue) {
        if (!handle.runId().equals(spec.runId())) throw new IllegalArgumentException("A verify must name its retained run");
        requireHeld(handle, binding);
        for (String role : List.of(AGENT, PUBLISHER)) {
            var state = client.inspectContainerCmd(containerOf(handle.runId(), role).orElseThrow()).exec().getState();
            if (!"exited".equals(state.getStatus())) throw new IllegalStateException("Verify requires stopped build processes");
        }
        long deadline = System.nanoTime() + spec.timeout().toNanos();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(RUN_ID_LABEL, spec.runId()); labels.put(PUBLICATION_HOLD_LABEL, binding.value());
        labels.put(ROLE_LABEL, VERIFY); labels.put(VERIFY_ATTEMPT_LABEL, spec.attemptId().toString());
        String volume = volumeName(spec.runId(), VerifyUnitSpec.VOLUME_PREFIX + spec.attemptId());
        try { client.inspectVolumeCmd(volume).exec(); }
        catch (NotFoundException absent) { client.createVolumeCmd().withName(volume).withLabels(labels).exec(); }

        String prepareId = verifyContainer(spec, spec.prepare(), labels, "prepare");
        Integer prepareExit = runToExit(prepareId, deadline);
        if (prepareExit == null) return new VerifyRun(null, VerifyRun.Prepare.FAILED, List.of(), true);
        if (prepareExit == 3) return new VerifyRun(null, VerifyRun.Prepare.CHECKPOINT_MISSING, List.of(), false);
        String head = preparedHead(prepareId);
        if (prepareExit != 0 || head == null) return new VerifyRun(null, VerifyRun.Prepare.FAILED, List.of(), false);

        List<VerifyRun.Check> checks = new ArrayList<>();
        for (int index = 0; index < spec.checks().size(); index++) {
            if (!mayContinue.getAsBoolean()) break;
            long started = System.nanoTime();
            String id;
            try { id = verifyContainer(spec, spec.checks().get(index), labels, "check-" + index); }
            catch (RuntimeException notCreated) { checks.add(new VerifyRun.Check(null, 0, List.of())); break; }
            Integer exit = runToExit(id, deadline);
            long wall = Duration.ofNanos(System.nanoTime() - started).toMillis();
            checks.add(new VerifyRun.Check(exit, wall, tailOf(id)));
            if (exit == null) return new VerifyRun(head, VerifyRun.Prepare.PREPARED, checks, true);
            if (exit != 0) break;
        }
        return new VerifyRun(head, VerifyRun.Prepare.PREPARED, checks, false);
    }
```

**Helpers:**
- **`verifyContainer`** names the container
  `volumeName(spec.runId(), "verify-" + attempt + "-" + suffix)`, which makes it idempotent across
  restarts like `publish-<permit>`. On a `ConflictException` it re-inspects the container by name. It uses
  a host config with these settings:
  - binds from `bindsFor`;
  - memory, CPUs and the `/tmp` tmpfs from `spec`;
  - the same pids, capability and no-new-privileges hardening as `hostConfigFor`.
- **`runToExit(id, deadline)`** starts the container, waits up to the remaining time, and returns the exit
  code. If the deadline passes, it calls `killQuietly` and returns `null`. A start failure also returns
  `null`.
- **`preparedHead(id)`** reads `logLinesOf(id)` and returns the `head` of the last
  `{"event":"prepared"...}` line, but only when it matches `[0-9a-f]{40}`.
- **`tailOf(id)`** uses `logContainerCmd(id).withStdOut(true).withStdErr(true).withTail(VERIFY_TAIL_LINES)`.
  It appends frames until `VERIFY_TAIL_CHARS` is reached, then stops appending. It clips each line to
  `VERIFY_LINE_CHARS` and adds the suffix `" [clipped]"`.

6. **`removeVerify(handle, attemptId)`:** force-remove every container labelled
   `VERIFY_ATTEMPT_LABEL=attemptId`, then remove the volume. `NotFoundException` is ignored.

- [ ] **Step 3: Push and read CI.** Commit message: `Run verify units against a held build on Docker`.
  The `service tests + packaging` job runs this module.

**Mutation guards:**
1. Drop the `discoverUnits` role filter. Expected to fail: `verifyResourcesKeepTheUnitHeldAndAreNeverItsHandle`.
2. Drop the verify kill in `cancel`. Expected to fail: `cancelStopsARunningCheck`.
3. Drop `withTail`. Expected to fail: `anEndlessLineIsClippedAndTheTailStaysBounded`. The 64 KiB char cap
   alone would still bound the size, so assert the line count too, as the test does.
4. Mount the handoff into checks. `VerifyUnitSpec` refuses that (Task 7), so this mutant targets
   `bindsFor`'s foreign-run branch instead. Expected to fail: `aCheckSeesNoHandoffAndNoUncommittedAgentFile`.

---

### Task 9: The run worker verifies (`spire-run-worker`)

**Files:**
- Create: `spire-run-worker/src/main/java/dev/codespire/runworker/VerifyOutcomes.java` (pure mapping)
- Create: `spire-run-worker/src/main/java/dev/codespire/runworker/WorkVerifyStore.java`
- Create: `spire-run-worker/src/main/java/dev/codespire/runworker/WorkVerifyWorker.java`
- Create: `spire-run-worker/src/main/java/dev/codespire/runworker/RunVerificationSerializer.java`
- Create: `spire-run-worker/src/main/resources/db/migration/V5__work_verify.sql`
- Modify: `RunUnitBuilder.java` (add `verifyUnit`, and the checkpoint mount + env on init)
- Modify: `RunDispatcher.java` (route `VerifyWork`)
- Modify: `WorkRunWorker.java` (retire the superseded held run after a checkpoint start becomes ready; skip verify containers in `localUnit`)
- Modify: `RunControlListener.java` (log and ignore `VerifyWork` on the control topic, as for execution)
- Modify: `src/main/resources/application.yml` (outgoing `run-verifications-out` → `cs.run-verifications`)
- Modify tests: `MessagingChannelsAreDeclaredTest`, `DeserializersNeverThrowTest` if it enumerates command types
- Test: `VerifyOutcomesTest.java`, `WorkVerifyWorkerTest.java`, `RunUnitBuilderVerifyTest.java`

**Interfaces — Consumes:** Tasks 1, 4, 7. **Produces:**
```java
final class VerifyOutcomes { static WorkVerification classify(RunCommand.VerifyWork command, VerifyRun run); }
public RunUnitSpec RunUnitBuilder.buildHeld(...)            // unchanged signature; init gains checkpoint mount when command.execution().startFromRunId()!=null
public VerifyUnitSpec RunUnitBuilder.verifyUnit(RunCommand.ExecuteWorkRun held, RunCommand.VerifyWork command)
public CompletionStage<Void> WorkVerifyWorker.execute(Message<RunCommand> message, RunCommand.VerifyWork command)
@Scheduled public void WorkVerifyWorker.recover()
```

- [ ] **Step 1: Failing tests for the pure mapping** (`VerifyOutcomesTest`)

```java
class VerifyOutcomesTest {
    final UUID attempt=UUID.randomUUID();final String head="b".repeat(40);
    RunCommand.VerifyWork command(String... commands){return new RunCommand.VerifyWork("TEST-run",new WorkRunBinding("TEST-item",1,UUID.randomUUID(),"a".repeat(64)),attempt,head,List.of(commands),60);}
    VerifyRun.Check exit(Integer code){return new VerifyRun.Check(code,5,List.of("TEST-line"));}
    WorkVerification classify(VerifyRun run,String... commands){return VerifyOutcomes.classify(command(commands),run);}

    @Test void everyCheckExitingZeroPasses(){assertTrue(classify(new VerifyRun(head,VerifyRun.Prepare.PREPARED,List.of(exit(0),exit(0)),false),"a","b").passed());}
    @Test void aNonZeroExitFails(){assertEquals("check_failed",classify(new VerifyRun(head,VerifyRun.Prepare.PREPARED,List.of(exit(0),exit(2)),false),"a","b","c").reason());}
    @Test void exits126And127AreAMissingTool(){
        assertEquals("tool_missing",classify(new VerifyRun(head,VerifyRun.Prepare.PREPARED,List.of(exit(127)),false),"a").reason());
        assertEquals("tool_missing",classify(new VerifyRun(head,VerifyRun.Prepare.PREPARED,List.of(exit(126)),false),"a").reason());
    }
    @Test void aTimeoutIsUnverifiedNotFailed(){
        var v=classify(new VerifyRun(head,VerifyRun.Prepare.PREPARED,List.of(exit(null)),true),"a");
        assertEquals(WorkVerification.Outcome.UNVERIFIED,v.outcome());assertEquals("timed_out",v.reason());
    }
    @Test void aCheckThatNeverStartedCouldNotRun(){assertEquals("verify_could_not_run",classify(new VerifyRun(head,VerifyRun.Prepare.PREPARED,List.of(exit(null)),false),"a").reason());}
    @Test void noCommandsIsUnverifiedEvenWhenNothingFailed(){assertEquals("no_checks_declared",classify(new VerifyRun(head,VerifyRun.Prepare.PREPARED,List.of(),false)).reason());}
    @Test void aMissingCheckpointIsNamed(){assertEquals("checkpoint_missing",classify(new VerifyRun(null,VerifyRun.Prepare.CHECKPOINT_MISSING,List.of(),false),"a").reason());}
    @Test void aPreparedHeadOtherThanAskedIsNeverPassed(){
        assertEquals("checkpoint_missing",classify(new VerifyRun("c".repeat(40),VerifyRun.Prepare.PREPARED,List.of(exit(0)),false),"a").reason());
    }
    @Test void fewerResultsThanCommandsWithoutAFailureCouldNotRun(){
        assertEquals("verify_could_not_run",classify(new VerifyRun(head,VerifyRun.Prepare.PREPARED,List.of(exit(0)),false),"a","b").reason());
    }
}
```

- [ ] **Step 2: Implement `VerifyOutcomes`**

```java
package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.work.WorkVerification;
import dev.codespire.runtime.VerifyRun;
import java.util.ArrayList;
import java.util.List;

/** The one place a verify unit's observations become an outcome (spec §3.2). Never infers PASSED. */
final class VerifyOutcomes {
    private VerifyOutcomes() {}
    static WorkVerification classify(RunCommand.VerifyWork command, VerifyRun run) {
        List<WorkVerification.CheckResult> checks = new ArrayList<>();
        for (int i = 0; i < run.checks().size(); i++) {
            VerifyRun.Check check = run.checks().get(i);
            String tail = String.join("\n", check.tail());
            if (tail.length() > WorkVerification.MAX_TAIL_CHARS) tail = tail.substring(tail.length() - WorkVerification.MAX_TAIL_CHARS);
            checks.add(new WorkVerification.CheckResult(command.commands().get(i), check.exitCode(), check.wallMillis(), tail));
        }
        String reason = reason(command, run);
        WorkVerification.Outcome outcome = reason == null ? WorkVerification.Outcome.PASSED
                : "check_failed".equals(reason) ? WorkVerification.Outcome.FAILED : WorkVerification.Outcome.UNVERIFIED;
        return new WorkVerification(command.attemptId(), command.head(), outcome, reason, checks);
    }
    private static String reason(RunCommand.VerifyWork command, VerifyRun run) {
        if (command.commands().isEmpty()) return "no_checks_declared";
        if (run.prepare() == VerifyRun.Prepare.CHECKPOINT_MISSING) return "checkpoint_missing";
        if (run.prepare() == VerifyRun.Prepare.FAILED) return run.timedOut() ? "timed_out" : "verify_could_not_run";
        if (!command.head().equals(run.preparedHead())) return "checkpoint_missing";
        if (run.timedOut()) return "timed_out";
        for (VerifyRun.Check check : run.checks()) {
            if (check.exitCode() == null) return "verify_could_not_run";
            if (check.exitCode() == 126 || check.exitCode() == 127) return "tool_missing";
            if (check.exitCode() != 0) return "check_failed";
        }
        return run.checks().size() == command.commands().size() ? null : "verify_could_not_run";
    }
}
```
When the verify unit does not run at all (no commands, refused, or no local unit), the worker builds a
`VerifyRun` with `Prepare.FAILED` or an empty check list, and passes it through this same method. Then there
is one path to an outcome.

- [ ] **Step 3: Migration `V5__work_verify.sql`**

```sql
-- One row per verify attempt this worker claimed (M4). The claim precedes any container, the result is
-- written before it is sent, and a restart that finds a row still 'running' reports verify_could_not_run.
CREATE TABLE runworker.work_verify (
    attempt_id UUID PRIMARY KEY,
    run_id TEXT NOT NULL,
    command BYTEA NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('running','finished')),
    result BYTEA,
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (state <> 'finished' OR result IS NOT NULL)
);
CREATE INDEX work_verify_unsent ON runworker.work_verify(created_at) WHERE state='finished' AND sent_at IS NULL;
```

- [ ] **Step 4: `WorkVerifyStore`.** Model it on `WorkRunStore`, with the same encryption and the AAD
  `work-verify:<attemptId>:<part>`. It has these methods:
  - `boolean claim(RunCommand.VerifyWork command)`. In one transaction it runs
    `INSERT INTO runworker.run_claim(run_id,slot) VALUES (?, 'verify:'||?) ON CONFLICT DO NOTHING`, then
    inserts the `running` row. It returns `false` when the claim already existed.
  - `void finish(UUID attempt, RunVerification.RunWorkVerified result)` sets `state='finished'` and stores
    the encrypted result.
  - `List<RunVerification.RunWorkVerified> unsent()` and `void sent(UUID attempt)` form the outbox.
  - `List<RunCommand.VerifyWork> running()` is for recovery.

- [ ] **Step 5: `RunUnitBuilder.verifyUnit` and the checkpoint init.** Write
  `RunUnitBuilderVerifyTest` first. It asserts:
  - `prepare` gets the publisher image and argv `spire-verify-prepare`;
  - `prepare` gets the read credential, plus env `SPIRE_CHECKPOINT_HEAD=<command head>`,
    `SPIRE_HANDOFF_DIR=/handoff` and `SPIRE_WORKSPACE_DIR=/workspace`;
  - `prepare` mounts handoff read-only and `verify-<attempt>` writable;
  - each check gets the held build's `agentImage`, entrypoint `["/bin/sh","-c"]` and argv `[command]`;
  - each check's environment equals `enterprise.environment()` only, and mounts only the verify volume;
  - the timeout equals `command.timeoutSeconds()`;
  - a held execution with `startFromRunId` gives init the mount `Mount.ofRun(previous, HANDOFF, "/checkpoint")`
    and the env `SPIRE_CHECKPOINT_HEAD` and `SPIRE_CHECKPOINT_DIR=/checkpoint`;
  - a timeout above `max-wall-clock-seconds` throws `IllegalArgumentException`.

  Then implement it:
```java
    public VerifyUnitSpec verifyUnit(RunCommand.ExecuteWorkRun held, RunCommand.VerifyWork command) {
        if (!held.runId().equals(command.runId()) || !held.work().equals(command.work()))
            throw new IllegalArgumentException("A verify must name the held build it checks");
        if (command.timeoutSeconds() > maxWallClockSeconds)
            throw new IllegalArgumentException("verify time limit " + command.timeoutSeconds() + "s is over this worker's " + maxWallClockSeconds + "s");
        var execution = held.execution();
        Credentials.Scm scm = credentials.scm(execution.runId(), execution.scmCredential());
        String volume = VerifyUnitSpec.VOLUME_PREFIX + command.attemptId();
        ContainerSpec prepare = new ContainerSpec(publisherImage, List.of("spire-verify-prepare"),
                Map.of("SPIRE_REMOTE_URI", execution.remoteUri(), "SPIRE_BASE_COMMIT", execution.baseCommit(),
                        "SPIRE_CLONE_USERNAME", scm.readUsername(), "SPIRE_CLONE_SECRET", scm.readSecret(),
                        "SPIRE_CHECKPOINT_HEAD", command.head(), "SPIRE_HANDOFF_DIR", "/handoff",
                        "SPIRE_WORKSPACE_DIR", "/workspace", "SPIRE_BUNDLE_MAX_BYTES", Long.toString(BUNDLE_MAX_BYTES)),
                List.of(Mount.readOnly(HANDOFF, "/handoff"), Mount.writable(volume, "/workspace")));
        List<ContainerSpec> checks = command.commands().stream().map(line -> new ContainerSpec(execution.agentImage(),
                List.of(line), Map.of(), List.of(Mount.writable(volume, "/workspace")), List.of("/bin/sh", "-c"))).toList();
        return new VerifyUnitSpec(command.runId(), command.attemptId(), prepare, checks, enterprise.environment(),
                MEMORY_BYTES, NANO_CPUS, DISK_BYTES, Duration.ofSeconds(command.timeoutSeconds()));
    }
```
In `build(...)`, when `command.startFromRunId() != null`, the init spec gets two extra environment entries
(`SPIRE_CHECKPOINT_HEAD`, `SPIRE_CHECKPOINT_DIR=/checkpoint`) and the extra mount
`Mount.ofRun(command.startFromRunId(), HANDOFF, "/checkpoint")`. Because `Map.of` caps at 10 pairs, build
the init env in a `LinkedHashMap`.

- [ ] **Step 6: `WorkVerifyWorker` tests first** (`WorkVerifyWorkerTest`). Use the `WorkRunWorkerTest` fake
  runtime, in which Task 7 added `nextVerify`. Cover these cases:
  - a redelivered `VerifyWork` claims once and runs once;
  - a held run that is absent locally gives `checkpoint_missing`;
  - a revoked held run gives `verify_could_not_run`;
  - zero commands give `no_checks_declared`, and `verifyHeld` is never called;
  - `removeVerify` is called after a run, including when `verifyHeld` threw;
  - after a simulated restart, a `running` row with no live attempt is reported as `verify_could_not_run`,
    and `removeVerify` is called for it;
  - a result is written before it is sent: when `report` throws, the row stays unsent and `flush` resends it.

- [ ] **Step 7: Implement `WorkVerifyWorker`**

```java
@ApplicationScoped
public class WorkVerifyWorker {
    private static final Logger LOG = Logger.getLogger(WorkVerifyWorker.class);
    @Inject WorkVerifyStore store;
    @Inject WorkRunStore runs;
    @Inject RunUnitBuilder builder;
    @Inject RunRuntime runtime;
    @Inject @Channel("run-verifications-out") Emitter<Record<String, RunVerification>> results;
    @ConfigProperty(name = "spire.run.result-ack-seconds") long ackSeconds;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public CompletionStage<Void> execute(Message<RunCommand> message, RunCommand.VerifyWork command) {
        if (!store.claim(command)) { LOG.infof("verify %s already claimed; redelivery", command.attemptId()); return message.ack(); }
        active.add(command.attemptId());
        CompletionStage<Void> acked = message.ack();
        try { store.finish(command.attemptId(), new RunVerification.RunWorkVerified(command.runId(), command.work(), VerifyOutcomes.classify(command, observe(command)))); }
        finally { active.remove(command.attemptId()); flush(); }
        return acked;
    }

    private VerifyRun observe(RunCommand.VerifyWork command) {
        if (command.commands().isEmpty()) return new VerifyRun(null, VerifyRun.Prepare.PREPARED, List.of(), false);
        if (!(runtime instanceof PublicationRuntime publication)) return couldNotRun();
        var held = runs.find(command.runId()).filter(h -> h.execution().work().equals(command.work()));
        if (held.isEmpty()) return new VerifyRun(null, VerifyRun.Prepare.CHECKPOINT_MISSING, List.of(), false);
        if (runs.revoked(held.get().execution())) return couldNotRun();
        var handle = runtime.discoverUnits().stream().filter(h -> h.runId().equals(command.runId())).findFirst();
        if (handle.isEmpty()) return new VerifyRun(null, VerifyRun.Prepare.CHECKPOINT_MISSING, List.of(), false);
        PublicationKey key = new PublicationKey(command.work().publicationKey());
        try {
            VerifyUnitSpec spec = builder.verifyUnit(held.get().execution(), command);
            return publication.verifyHeld(handle.get(), key, spec, () -> !runs.revoked(held.get().execution()));
        } catch (RuntimeException failure) {
            LOG.warnf(failure, "verify %s for %s could not run", command.attemptId(), command.runId());
            return couldNotRun();
        } finally {
            try { publication.removeVerify(handle.get(), command.attemptId()); }
            catch (RuntimeException leftover) { LOG.warnf(leftover, "verify %s resources were not removed", command.attemptId()); }
        }
    }
    private static VerifyRun couldNotRun() { return new VerifyRun(null, VerifyRun.Prepare.FAILED, List.of(), false); }

    @Scheduled(every = "${spire.run.work-recovery:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void recover() {
        for (RunCommand.VerifyWork stranded : store.running()) {
            if (active.contains(stranded.attemptId())) continue;
            runtime.discoverUnits().stream().filter(h -> h.runId().equals(stranded.runId())).findFirst()
                    .ifPresent(h -> { if (runtime instanceof PublicationRuntime p) p.removeVerify(h, stranded.attemptId()); });
            store.finish(stranded.attemptId(), new RunVerification.RunWorkVerified(stranded.runId(), stranded.work(),
                    VerifyOutcomes.classify(stranded, couldNotRun())));
        }
        flush();
    }

    public void flush() {
        for (RunVerification.RunWorkVerified result : store.unsent()) {
            try {
                results.send(Record.of(result.runId(), (RunVerification) result)).toCompletableFuture().get(ackSeconds, TimeUnit.SECONDS);
                store.sent(result.verification().attemptId());
            } catch (Exception notSent) { LOG.warnf("verification %s not sent yet; retried on the next sweep", result.verification().attemptId()); return; }
        }
    }
}
```
- `classify(command, couldNotRun())` with commands present reaches `Prepare.FAILED` → `verify_could_not_run`.
  With none, it is `no_checks_declared`.
- **Ack placement.** The message is acked after the claim commits, as in `WorkRunWorker.execute`. The run
  is synchronous inside the ordered blocking consumer, which keeps the one-slot rule.
- **`RunDispatcher.handle`:** add
  `if (command instanceof RunCommand.VerifyWork verify) return verifier.execute(message, verify);`. It goes
  before the `ExecuteRun` branch, with `@Inject WorkVerifyWorker verifier;`.
- **`WorkRunWorker.localUnit`:** keep it as it is. The runtime's `discoverUnits` already skips verify
  containers (Task 8).
- **Retiring the superseded held run.** In `WorkRunWorker.execute`, after `store.buildResult(result)` and
  only when the result is a `RunWorkReady` and `command.execution().startFromRunId()` is non-null:
  - find the old held row;
  - call `publication.destroyHeld(localUnit(old).orElseThrow(), new PublicationKey(old.execution().work().publicationKey()))`;
  - mark the old row finished with a new `store.retire(oldRunId)`, which sets `state='finished'` and
    `final_sent_at=now()`, so no terminal result is ever sent for it.

  Add a `WorkRunWorkerTest` case `aCheckpointStartRetiresThePreviousHeldRunOnceReady`.

- [ ] **Step 8: Messaging config.** In `application.yml`, add under outgoing:

```yaml
      run-verifications-out:
        connector: smallrye-kafka
        topic: cs.run-verifications
        key:
          serializer: org.apache.kafka.common.serialization.StringSerializer
        value:
          serializer: dev.codespire.runworker.RunVerificationSerializer
        waitForWriteCompletion: true
```
`RunVerificationSerializer extends ObjectMapperSerializer<RunVerification> {}`. Update
`MessagingChannelsAreDeclaredTest` to expect the channel.

- [ ] **Step 9: Push and read CI.** Commit message: `Verify held builds in the run worker`.

**Mutation guards:**
1. `if (!command.head().equals(run.preparedHead()))`. Expected to fail: `aPreparedHeadOtherThanAskedIsNeverPassed`.
2. `run.checks().size() == command.commands().size()`. Expected to fail:
   `fewerResultsThanCommandsWithoutAFailureCouldNotRun`.
3. The `active.contains` skip in `recover`. Expected to fail: a `WorkVerifyWorkerTest` case
   `aRunningVerifyIsNotRecoveredUnderItself`. Add that case.
4. The `126` in `tool_missing`. Expected to fail: `exits126And127AreAMissingTool`.

---

### Task 10: Build setup stores the checks (`spire-orchestrator`, storage)

**Files:**
- Create: `spire-orchestrator/src/main/resources/db/migration/V87__verify.sql`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/BuildDefaults.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RepositoryBuildResource.java` (`Options` gains `long verifyMaxSeconds`)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkPreparationSweep.java:247-253`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/factory/BuildDefaultsTest.java`, `WorkPreparationSweepTest.java`

**Interfaces — Produces:**
```java
public record Defaults(long revision, String baseBranch, String harness, String model, String effort, String payWith,
                       List<String> verifyCommands, long verifyTimeoutSeconds, String updatedBy, Instant updatedAt)
public record Input(long expectedRevision, String baseBranch, String harness, String model, String effort, String payWith,
                    List<String> verifyCommands, Long verifyTimeoutSeconds)   // null timeout = 1800
```

- [ ] **Step 1: Migration**

```sql
-- M4 verify, slice 1. The operator declares a repository's checks (spec §2): a verify command is control,
-- and ADR-036 keeps control out of repository free text. Copied into the preparation and bound by it
-- (WorkPreparation version 5), so editing this row never changes what an open decision approved.
ALTER TABLE repository_build_defaults ADD COLUMN verify_commands TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE repository_build_defaults ADD COLUMN verify_timeout_seconds INTEGER NOT NULL DEFAULT 1800
    CHECK (verify_timeout_seconds > 0);

-- One row per verify attempt, like work_run_effect for builds. The result's check tails can quote source,
-- so they are stored Tink-encrypted (AAD work-verify-result:<attempt_id>).
CREATE TABLE work_verify_effect (
    attempt_id UUID PRIMARY KEY REFERENCES work_phase_attempt(id),
    work_item_id TEXT NOT NULL REFERENCES work_item(id),
    generation BIGINT NOT NULL,
    run_id TEXT NOT NULL REFERENCES factory_run(run_id),
    head TEXT NOT NULL CHECK (head ~ '^[0-9a-f]{40}$'),
    state TEXT NOT NULL CHECK (state IN ('pending','sent','uncertain','reported','applied','refused')),
    outcome TEXT CHECK (outcome IN ('PASSED','FAILED','UNVERIFIED')),
    reason TEXT,
    result BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (state NOT IN ('reported','applied') OR (result IS NOT NULL AND outcome IS NOT NULL))
);
CREATE INDEX work_verify_open ON work_verify_effect(created_at) WHERE state IN ('pending','reported');
CREATE INDEX work_verify_item ON work_verify_effect(work_item_id, generation, created_at);
```

- [ ] **Step 2: Failing tests** (add to `BuildDefaultsTest`; follow its existing setup)

```java
    @Test void savesTheChecksInOrderAndTheirLimit(){
        var saved=defaults.save(repository,input(List.of("./gradlew check","npm test"),900L),"TEST-operator");
        assertEquals(List.of("./gradlew check","npm test"),saved.verifyCommands());assertEquals(900,saved.verifyTimeoutSeconds());
    }
    @Test void blankLinesAreNotCommands(){
        assertEquals(List.of("make"),defaults.save(repository,input(Arrays.asList("make"," ","",null),null),"TEST-operator").verifyCommands());
    }
    @Test void aLimitAboveTheWallClockIsRefused(){
        var refused=assertThrows(BuildDefaults.Refused.class,()->defaults.save(repository,input(List.of("make"),config.wallClockSeconds()+1),"TEST-operator"));
        assertEquals("verify_timeout_out_of_range",refused.reason());
    }
    @Test void aCommandWithALineBreakIsRefused(){
        assertEquals("verify_command_invalid",assertThrows(BuildDefaults.Refused.class,()->defaults.save(repository,input(List.of("a\nb"),null),"TEST-operator")).reason());
    }
    @Test void noChecksIsASaveableSetup(){assertEquals(List.of(),defaults.save(repository,input(List.of(),null),"TEST-operator").verifyCommands());}
```

- [ ] **Step 3: Implement `BuildDefaults`**

- Add the components. `Defaults.none()` passes `List.of(), 1800`.
- The older `Input` constructors pass `List.of(), null`.
- In `save`, before the database work:

```java
        List<String> commands = input.verifyCommands() == null ? List.of() : input.verifyCommands().stream()
                .filter(c -> c != null && !c.isBlank()).map(String::strip).toList();
        if (commands.size() > 20 || commands.stream().anyMatch(c -> c.length() > 1000 || c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0))
            throw new Refused("verify_command_invalid");
        long timeout = input.verifyTimeoutSeconds() == null ? Math.min(1800, config.wallClockSeconds()) : input.verifyTimeoutSeconds();
        if (timeout < 60 || timeout > config.wallClockSeconds()) throw new Refused("verify_timeout_out_of_range");
```

- Write `verify_commands` with `ps.setArray(n, c.createArrayOf("text", commands.toArray()))` and
  `verify_timeout_seconds` with `ps.setLong`.
- Read them back with `(String[]) rs.getArray(i).getArray()`.

- [ ] **Step 4: The preparation copies them.** In `WorkPreparationSweep.attempt`, build the preparation
  with `WorkPreparation.VERIFY_BINDING` and the two new arguments: `setup.verifyCommands()` and
  `setup.verifyTimeoutSeconds()`. Add to `WorkPreparationSweepTest`:
  `aPreparationCopiesTheChecksAndALaterEditDoesNotChangeIt`. It saves the checks, prepares, saves other
  checks, and asserts that the item's `preparation().verifyCommands()` is still the first list.

- [ ] **Step 5: `Options.verifyMaxSeconds`.** Set it to `config.wallClockSeconds()`.

- [ ] **Step 6: Push and read CI.** Commit message: `Store a repository's checks and copy them into the preparation`.

**Mutation guards:**
1. The blank filter. Expected to fail: `blankLinesAreNotCommands`.
2. The `> config.wallClockSeconds()` bound. Expected to fail: `aLimitAboveTheWallClockIsRefused`.
3. The `VERIFY_BINDING` argument in the sweep (back to `PAY_WITH_BINDING`). Expected to fail:
   `aPreparationCopiesTheChecksAndALaterEditDoesNotChangeIt`.

---

### Task 11: The orchestrator runs verify and applies its result (`spire-orchestrator`, flow)

**Files:**
- Modify: `work/WorkItemStore.java:162` (project `work_verify_effect` on verify `PHASE_STARTED`)
- Modify: `work/WorkPhaseCapability.java`
- Create: `work/WorkVerifyDispatcher.java`
- Create: `work/WorkVerifyResults.java` (consumer + apply)
- Create: `factory/RunVerificationDeserializer.java`, `factory/RunVerificationSerializer.java` (for the DLQ requeue, mirroring `RunResultSerializer`)
- Modify: `work/WorkItemTransitions.java` (`advance` result-gate path; `answer` retry/stop branch; new `verified(...)`)
- Modify: `work/WorkDelivery.java:44,198` (require `PASSED`)
- Modify: `dlq/DlqTopics.java` (`VerifyWork` → `cs.run-commands`; `RunWorkVerified` → `cs.run-verifications`)
- Modify: `src/main/resources/application.yml` (incoming `run-verifications-in`)
- Modify tests: `WorkDeliveryIT.java`, `WorkReviewIT.java` (delete the TEST-only capability override; drive a real `RunWorkVerified`), `DlqTopicsTest.java`, `WorkItemRunBridgeTest.java:96,113`
- Test: `work/WorkVerifyIT.java` (new)

**Interfaces — Consumes:** Tasks 1–3, 10. **Produces:**
```java
// WorkItemTransitions
public Outcome verified(String id, RunVerification.RunWorkVerified result)   // PASSED -> complete; else result gate
// answer(...) branch: gate.phase()=="verify" && VERIFY_RESULT_REASONS.contains(current.reason())
```

- [ ] **Step 1: Failing ITs (`WorkVerifyIT extends WorkPreparedFixture`).** Copy the setup of
  `WorkDeliveryIT` (`buildForVerification`, the `heldCommands` capture and `saga`) but **without** the
  capability override. Mock `WorkVerifyTransport` with `QuarkusMock` to capture `VerifyWork` commands.
  The class is in Step 3.

```java
    @Test void startingVerifySendsTheBoundChecksOnce() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();verifier.drain();
        assertEquals(1,sent.size());var command=sent.getFirst();
        assertEquals(store.load(id).progress().execution().head(),command.head());
        assertEquals(store.load(id).preparation().verifyCommands(),command.commands());
        assertEquals(1,count("SELECT count(*) FROM work_verify_effect WHERE work_item_id=? AND state='sent'",id));
    }
    @Test void passedCompletesVerifyAndDeliveryMayStart() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();
        results.apply(passed(sent.getFirst()));
        assertEquals("deliver",store.load(id).phase());
        assertTrue(store.load(id).progress().execution().verification().passed());
    }
    @Test void failedOpensAResultGateEvenInAutonomous() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();
        results.apply(failed(sent.getFirst()));
        var item=store.load(id);assertEquals("waiting_approval",item.workflowStatus());assertEquals("verify_failed",item.reason());
        assertEquals("verify",item.gate().phase());assertEquals(0,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=?",id));
    }
    @Test void retryBuildRewindsToBuildAndCountsARun() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();results.apply(failed(sent.getFirst()));
        var gate=store.load(id).gate();long runs=store.load(id).progress().runs();
        assertEquals(200,transitions.answer(gate.id(),gate.version(),"TEST-retry",true,"TEST retry","TEST-delivery-admin").status());
        var item=store.load(id);assertEquals("build",item.phase());assertEquals("active",item.workflowStatus());assertEquals(runs+1,item.progress().runs());
    }
    @Test void retryStopsAtTheRunLimit() throws Exception {
        String id=buildForVerification("autonomous-one-run");   // a fixture profile with maxRunsPerItem=1
        verifier.drain();results.apply(failed(sent.getFirst()));var gate=store.load(id).gate();
        transitions.answer(gate.id(),gate.version(),"TEST-retry",true,null,"TEST-delivery-admin");
        assertEquals("policy_cap_reached",store.load(id).reason());
    }
    @Test void stopNamesTheOperatorsDecision() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();results.apply(unverified(sent.getFirst(),"tool_missing"));
        var gate=store.load(id).gate();transitions.answer(gate.id(),gate.version(),"TEST-stop",false,null,"TEST-delivery-admin");
        assertEquals("stopped",store.load(id).workflowStatus());assertEquals("verify_stopped_by_operator",store.load(id).reason());
    }
    @Test void aPullRequestReviewCannotAnswerAVerifyGate() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();results.apply(failed(sent.getFirst()));
        var gate=store.load(id).gate();
        var outcome=transitions.answer(new ResolveGate(gate.id(),gate.version(),"TEST-pr-review",true,null,"TEST-reviewer",
                ResolveGate.Channel.PR_REVIEW,gate.generation(),gate.artifact()));
        assertEquals(409,outcome.status());assertEquals("pr_review_requires_land_gate",outcome.reason());
        assertEquals("verify",store.load(id).phase());
    }
    @Test void aResultForASuspendedItemOpensNoGate() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();
        takeOver(id);   // the same WorkItemControl call WorkDeliveryIT's takeover tests make; read it and reuse it
        assertDoesNotThrow(()->results.apply(failed(sent.getFirst())));
        var item=store.load(id);assertEquals("suspended",item.workflowStatus());
        assertTrue(item.gate()==null || !"OPEN".equals(item.gate().state()),"a suspended item gets no result gate");
        assertEquals("applied",string("SELECT state FROM work_verify_effect WHERE work_item_id=?",id));
    }
    @Test void aDuplicateResultIsAppliedOnce() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();var r=passed(sent.getFirst());
        results.apply(r);results.apply(r);assertEquals("deliver",store.load(id).phase());
    }
    @Test void aResultForAnotherHeadIsRefused() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();var command=sent.getFirst();
        var other=new RunCommand.VerifyWork(command.runId(),command.work(),command.attemptId(),"c".repeat(40),command.commands(),command.timeoutSeconds());
        results.apply(passed(other));
        assertEquals("verify",store.load(id).phase());assertEquals("active",store.load(id).workflowStatus());
        assertEquals("sent",string("SELECT state FROM work_verify_effect WHERE work_item_id=?",id));
    }
    @Test void theTailIsStoredEncrypted() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();results.apply(failed(sent.getFirst()));
        assertFalse(new String(bytes("SELECT result FROM work_verify_effect WHERE work_item_id=?",id),java.nio.charset.StandardCharsets.ISO_8859_1).contains("TEST-tail"));
    }
```

Helpers:
- `passed(command)` builds a `RunWorkVerified` whose checks all exit 0, one per command, each with tail
  `"TEST-tail"`.
- `failed(command)` has a check exiting 1.
- `unverified(command, reason)` has no checks.

The fixture's prepared repository must have build defaults with at least one check. Extend
`WorkPreparedFixture.register` to save `List.of("TEST-check")` and timeout 60 into the preparation's version
5 binding. If the fixture registers preparations directly, construct them with `VERIFY_BINDING`.

- [ ] **Step 2: Project the effect.** In `WorkItemStore.projectExecution`, after the deliver block:

```java
        if("verify".equals(item.phase()) && "PHASE_STARTED".equals(item.milestone()) && progress.execution()!=null)
            try(PreparedStatement ps=c.prepareStatement("INSERT INTO work_verify_effect(attempt_id,work_item_id,generation,run_id,head,state) VALUES (?,?,?,?,?,'pending') ON CONFLICT DO NOTHING")) {
                ps.setObject(1,progress.attemptId());ps.setString(2,item.workItemId());ps.setLong(3,item.generation());
                ps.setString(4,progress.execution().runId());ps.setString(5,progress.execution().head());ps.executeUpdate();
            }
```
Also add `UPDATE work_verify_effect SET state='refused',reason='work_item_invalidated' WHERE work_item_id=? AND state='pending'`
to the invalidation block at `:117-125`.

- [ ] **Step 3: Capability and transport**

```java
            case "verify" -> item.preparation()!=null && item.progress().execution()!=null && verifies.available();
```
`@Inject WorkVerifyTransport verifies;` is a new tiny bean, like `WorkPublicationTransport`:

```java
@ApplicationScoped
public class WorkVerifyTransport {
    @Inject RunCommandEmitter emitter;
    public boolean available() { return true; }
    public RunLaunch.Outcome dispatch(RunCommand.VerifyWork command) {
        try { emitter.dispatch(command); return new RunLaunch.Dispatched(); }
        catch(IllegalStateException failure) {
            return failure instanceof BrokerAckFailure ack && !ack.mayHaveLanded()?new RunLaunch.DefiniteMiss(failure):new RunLaunch.Uncertain(failure);
        }
    }
}
```
`WorkDeliveryIT` and `WorkReviewIT` build `new WorkPhaseCapability(){...}` by hand. Once their overrides are
deleted, they use the real bean, so no field assignment is needed.

- [ ] **Step 4: `WorkVerifyDispatcher`.** It follows `WorkRunDispatcher`'s claim-then-send shape:
  - `@Scheduled(every="${spire.work-run-interval:5s}")` runs `drain()`, which selects `pending` rows.
  - `dispatch(attempt)` works in `QuarkusTransaction.requiringNew()`. It locks the item and re-reads the row
    `FOR UPDATE`.
  - It refuses with `work_item_changed` unless all of these hold:
    - the generation matches;
    - the attempt is current;
    - the phase is `verify`;
    - the status is `active`;
    - `progress.execution().head()` equals the row's head.
  - It sets `state='uncertain'`, builds
    `new RunCommand.VerifyWork(execution.runId(),execution.build(),attempt,execution.head(),item.preparation().verifyCommands(),item.preparation().verifyTimeoutSeconds()==0?1800:item.preparation().verifyTimeoutSeconds())`,
    then commits.
  - Outside the transaction it calls `transport.dispatch(command)` and maps the outcome:
    `Dispatched` → `sent`, `DefiniteMiss` → `pending`, `Uncertain` → stays `uncertain`.

  A version 4 preparation has no commands. It still verifies, and the worker answers `no_checks_declared`.

- [ ] **Step 5: `WorkVerifyResults`**

```java
@ApplicationScoped
public class WorkVerifyResults {
    private static final Logger LOG=Logger.getLogger(WorkVerifyResults.class);
    @Inject DataSource dataSource; @Inject EncryptionService encryption; @Inject ObjectMapper mapper; @Inject WorkItemTransitions transitions;

    @Incoming("run-verifications-in") @Blocking
    public CompletionStage<Void> onResult(Message<RunVerification> message) {
        if(message.getPayload() instanceof RunVerification.RunWorkVerified verified) {
            try { apply(verified); }
            catch(RuntimeException failure) { LOG.errorf(failure,"verification %s could not be applied",verified.verification().attemptId()); return message.nack(failure); }
        }
        return message.ack();
    }

    /** Stores the result once, then applies it. A redelivery finds 'applied' and does nothing. */
    public void apply(RunVerification.RunWorkVerified verified) {
        var v=verified.verification();String item;
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("""
                UPDATE work_verify_effect SET state='reported',outcome=?,reason=?,result=COALESCE(result,?)
                WHERE attempt_id=? AND run_id=? AND head=? AND state IN ('sent','uncertain','pending') RETURNING work_item_id""")) {
            ps.setString(1,v.outcome().name());ps.setString(2,v.reason());
            ps.setBytes(3,encryption.encrypt(mapper.writeValueAsBytes(verified),"work-verify-result:"+v.attemptId()));
            ps.setObject(4,v.attemptId());ps.setString(5,verified.runId());ps.setString(6,v.head());
            try(ResultSet rs=ps.executeQuery()){item=rs.next()?rs.getString(1):null;}
        }catch(SQLException failure){throw WorkSourceRegistry.database(failure);}
        catch(java.io.IOException failure){throw new IllegalStateException("Cannot encode verification",failure);}
        if(item==null){item=reportedItem(v.attemptId());if(item==null){refuseUnknown(verified);return;}}
        var outcome=transitions.verified(item,verified);
        if(outcome.status()!=503)markApplied(v.attemptId(),outcome.status()==200?null:outcome.reason());
    }
}
```
- `reportedItem(attempt)` returns the item id when the row is already `reported`. That covers the crash
  window between storing and applying. For an `applied` row it returns `null`, so a duplicate is a no-op.
- `refuseUnknown` logs and returns. It handles a result whose attempt, run or head matches no row; the
  "another head" IT asserts that the row stays `sent` and the phase stays `verify`.
- `markApplied` sets `state='applied',reason=?`.
- A scheduled `recover()` re-applies rows that are still `reported`.

- [ ] **Step 6: `WorkItemTransitions.verified` and the `advance` path**

```java
    /** Called only by the verify result consumer. PASSED completes verify; any other outcome opens the result gate. */
    public Outcome verified(String id,RunVerification.RunWorkVerified result) {
        WorkItemEvent item=require(id);var v=result.verification();
        var execution=item.progress().execution();
        if(execution==null || !execution.runId().equals(result.runId()))return new Outcome(409,"phase_evidence_mismatch",item);
        return complete(id,new PhaseResult(v.attemptId(),v.passed(),0,0,0,true,execution.verified(v)));
    }
```
In `advance`, replace the failed branch for verify. Where the code reads
`next=state(next,result.successful()?"awaiting_input":"failed",...)`, insert this before it:

```java
                    if(!result.successful() && "verify".equals(item.phase()) && result.execution()!=null && result.execution().verification()!=null) {
                        WorkProgress finished=next.progress().finish(result.wallSeconds(),result.costMillicents(),result.calls()).withExecution(result.execution());
                        next=WorkItemLifecycle.verifyResultGate(next.decision(next.policyRevision(),next.authority(),next.policy(),next.phase(),next.workflowStatus(),next.reason(),next.milestone(),next.gate(),finished),
                                item,history.size(),clock.now(),UUID.randomUUID(),result.execution().verification());
                        store.appendDecision(c,history,next,"phase-result:"+result.attemptId());
                        return new Outcome(200,next.reason(),next);
                    }
```
- **Why `validExecution` already accepts it:** `proof.equals(previous.verified(...))` is checked against
  the `UUID` form today. Change the `verify` case to
  `previous!=null && proof.verification()!=null && proof.equals(previous.verified(proof.verification())) && proof.verificationAttempt().equals(result.attemptId())`.
  With that change, `validExecution` accepts the result-gate path.
- **The TEST-only stored-history form stays readable** but can no longer complete verify. Delete the
  `verify(id)` helpers in `WorkDeliveryIT`/`WorkReviewIT`. Replace them with
  `verifier.drain(); results.apply(passed(sent.getFirst()));`, using a shared helper in
  `WorkPreparedFixture`.

- [ ] **Step 7: `answer` retry/stop.** In the `else` branch that builds `next=current.decision(...,"GATE_RESOLVED",...)`,
  replace the `if(approve){...} else next=state(next,"stopped","gate_rejected",...)` with:

```java
                    boolean verifyResult="verify".equals(gate.phase()) && WorkItemLifecycle.VERIFY_RESULT_REASONS.contains(current.reason());
                    if(approve) {
                        if(verifyResult)   // Retry build: the human chose to build again, which also stands in for a build: approve gate.
                            next=next.decision(next.policyRevision(),next.authority(),next.policy(),"build",next.workflowStatus(),next.reason(),next.milestone(),next.gate(),next.progress());
                        store.appendDecision(c,history,next,"gate-answer:"+gateId+":"+key);
                        history=store.history(id);
                        next=enterPrepared(c,history,next,true,now);
                        history=store.history(id);
                    }
                    else next=state(next,"stopped",verifyResult?"verify_stopped_by_operator":"gate_rejected","GATE_RESOLVED",next.gate(),next.progress());
```
The phase check `gate.phase().equals(current.phase())` runs earlier, and is still true at that point,
because the rewind happens after it.

- [ ] **Step 8: Delivery requires PASSED**

- `WorkDelivery.available`: change `execution.verificationAttempt()==null` to
  `execution.verification()==null || !execution.verification().passed()`.
- `refusal`, at `:198`: add the same condition to the `verified_build_required` test.
- Add to `WorkDeliveryIT`: `aVerificationWithoutAPassedOutcomeCannotDeliver`. It completes verify with the
  stored-history `verified(UUID)` form through `transitions.complete`, and the phase stays `verify` with
  `phase_evidence_mismatch`.

- [ ] **Step 9: Messaging and DLQ.** In `application.yml`, under incoming, add the following, mirroring
  `run-results-in`:

```yaml
      run-verifications-in:
        connector: smallrye-kafka
        topic: cs.run-verifications
        group:
          id: spire-orchestrator-run-verifications
        value:
          deserializer: dev.codespire.orchestrator.factory.RunVerificationDeserializer
        # Earliest: a result sent while the orchestrator restarts must not be skipped. Applying is
        # idempotent by attempt id (work_verify_effect.state).
        auto:
          offset:
            reset: earliest
        failure-strategy: dead-letter-queue
        dead-letter-queue:
          topic: cs.dlq
```
Then:
- `RunVerificationDeserializer` mirrors `RunResultDeserializer` and never throws.
- In `DlqTopics`, add `RUN_VERIFICATIONS = "cs.run-verifications"`, map `"RunWorkVerified"` to it, and add
  `"VerifyWork"` to `RUN_COMMAND_TYPES`.
- `DlqTopicsTest` gets two assertions for these mappings.

- [ ] **Step 10: Push and read CI.** Commit message: `Run verify for item builds and gate results that do not pass`.

**Mutation guards:**
1. `verifyResult` forced to `false` in `answer`. Expected to fail: `retryBuildRewindsToBuildAndCountsARun`.
2. The result-gate branch in `advance` removed (falls to `phase_failed`). Expected to fail:
   `failedOpensAResultGateEvenInAutonomous`.
3. `!execution.verification().passed()` in `WorkDelivery.refusal`. Expected to fail:
   `aVerificationWithoutAPassedOutcomeCannotDeliver`.
4. `head=?` in the `apply` UPDATE. Expected to fail: `aResultForAnotherHeadIsRefused`.
5. `verify_stopped_by_operator`. Expected to fail: `stopNamesTheOperatorsDecision`.

---

### Task 12: Retry build starts from the checkpoint with the failure in its prompt (`spire-orchestrator`)

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/WorkRunAssembly.java`
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/work/WorkVerifyHistory.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/work/WorkVerifyIT.java` (extend), `WorkVerifyHistoryTest.java`

**Interfaces — Produces:**
```java
public record Previous(String runId, String head, WorkVerification verification) {}
public Optional<Previous> WorkVerifyHistory.lastNotPassed(Connection c, String workItemId, long generation)
static String WorkVerifyHistory.promptSection(WorkVerification v)   // bounded to 8000 chars
```

- [ ] **Step 1: Failing tests**

```java
    // WorkVerifyIT
    @Test void aRetryStartsFromTheFailedCheckpointWithTheFailureInItsPrompt() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();var first=sent.getFirst();results.apply(failed(first));
        var gate=store.load(id).gate();transitions.answer(gate.id(),gate.version(),"TEST-retry",true,null,"TEST-delivery-admin");
        dispatcher.drain();var retry=heldCommands.getLast().execution();
        assertEquals(first.runId(),retry.startFromRunId());assertEquals(first.head(),retry.startFromHead());
        assertTrue(retry.prompt().contains("TEST-check"));assertTrue(retry.prompt().contains("TEST-tail"));
    }
    @Test void aRetryAfterAMissingCheckpointStartsFromTheBase() throws Exception {
        String id=buildForVerification("autonomous");verifier.drain();results.apply(unverified(sent.getFirst(),"checkpoint_missing"));
        var gate=store.load(id).gate();transitions.answer(gate.id(),gate.version(),"TEST-retry",true,null,"TEST-delivery-admin");
        dispatcher.drain();var retry=heldCommands.getLast().execution();
        assertNull(retry.startFromRunId());assertTrue(retry.prompt().contains("could not be read"));
    }
    // WorkVerifyHistoryTest
    @Test void theSectionIsBounded(){
        var huge=new WorkVerification(UUID.randomUUID(),"b".repeat(40),WorkVerification.Outcome.FAILED,"check_failed",
                List.of(new WorkVerification.CheckResult("TEST-check",1,1,"x".repeat(60_000))));
        assertTrue(WorkVerifyHistory.promptSection(huge).length()<=8000);
    }
```

- [ ] **Step 2: Implement**

`WorkVerifyHistory.lastNotPassed` runs:
```sql
SELECT run_id,head,result FROM work_verify_effect WHERE work_item_id=? AND generation=? AND state='applied' AND outcome<>'PASSED' ORDER BY created_at DESC LIMIT 1
```
It decrypts with AAD `work-verify-result:<attempt>`. Read the `attempt_id` column as well: the AAD needs it.

`promptSection`:
```java
    static String promptSection(WorkVerification v) {
        StringBuilder s=new StringBuilder("\n\nThe previous build of this task did not pass verification.\n");
        if("checkpoint_missing".equals(v.reason()))s.append("Its commits could not be read, so this build starts again from the base.\n");
        else s.append("This build starts from that build's last commit. Fix what the checks found.\n");
        for(var check:v.checks()) {
            s.append("\nCommand: ").append(check.command()).append("\nExit code: ").append(check.exitCode()==null?"did not run":check.exitCode());
            if(check.exitCode()!=null && check.exitCode()!=0 && !check.outputTail().isEmpty())s.append("\nLast output:\n").append(check.outputTail());
        }
        if(v.checks().isEmpty())s.append("\nReason: ").append(v.reason());
        return s.length()<=8000?s.toString():s.substring(0,7980)+"\n[output shortened]";
    }
```

In `WorkRunAssembly.assemble`, after `parse(...)`:
```java
        var previous=history.lastNotPassed(c,item.workItemId(),item.generation());
        String prompt=in.prompt()+previous.map(p->WorkVerifyHistory.promptSection(p.verification())).orElse("");
```
- Use `prompt` in place of `in.prompt()` in the `ExecuteRun` constructor.
- After the `paidBySignIn` line, add:

```java
        if(previous.isPresent() && !"checkpoint_missing".equals(previous.get().verification().reason()))
            command=command.fromCheckpoint(previous.get().runId(),previous.get().head());
```
`WorkRunAssembly` is in `factory`, so make `WorkVerifyHistory` and its methods `public`.

- [ ] **Step 3: Push and read CI.** Commit message: `Start a retried build from its checkpoint with the failure in its prompt`.

**Mutation guards:**
1. The `checkpoint_missing` exclusion. Expected to fail: `aRetryAfterAMissingCheckpointStartsFromTheBase`.
2. The 8000 cap. Expected to fail: `theSectionIsBounded`.

---

### Task 13: Build setup screen (`spire-ui`)

**Files:**
- Modify: `spire-ui/src/components/repositories/factory/buildDefaultsApi.ts`
- Modify: `spire-ui/src/components/repositories/factory/BuildStep.tsx`
- Modify: `spire-ui/src/components/repositories/factory/factoryFixtures.ts`
- Modify: `spire-ui/src/components/work-items/workPreparationApi.ts` (`Preparation` gains `verifyCommands?: string[]; verifyTimeoutSeconds?: number`)
- Modify: `spire-ui/src/components/work-items/DecisionEvidence.tsx:52-62` (a "Checks" row)
- Test: `spire-ui/src/components/repositories/factory/RepositoryFactory.test.tsx`, `DecisionPanel.test.tsx`

**Interfaces — Produces (TS):**
```ts
export interface BuildDefaults { /* existing */ verifyCommands?: string[]; verifyTimeoutSeconds?: number }
export interface BuildOptions { /* existing */ verifyMaxSeconds?: number }
saveBuildDefaults(repository, input: { expectedRevision: number; baseBranch: string; harness: string; model: string;
  effort: string | null; payWith: PayWith; verifyCommands: string[]; verifyTimeoutSeconds: number })
```

- [ ] **Step 1: Failing tests** (in `describe('build setup')`)

```tsx
  it('saves the check commands one per line and the verify time limit', async () => {
    renderFactory();
    await open();
    fireEvent.change(await screen.findByLabelText('Base branch', field), { target: { value: 'main' } });
    fireEvent.change(await screen.findByLabelText('Harness', field), { target: { value: 'codex' } });
    fireEvent.change(await screen.findByLabelText('Model', field), { target: { value: 'TEST-model' } });
    fireEvent.change(await screen.findByLabelText('Check commands', field), { target: { value: './gradlew check\n\n  npm test  \n' } });
    fireEvent.change(await screen.findByLabelText('Verify time limit (seconds)', field), { target: { value: '900' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save build setup' }));
    await waitFor(() => expect(build.saveBuildDefaults).toHaveBeenCalledWith(repository.id, expect.objectContaining({
      verifyCommands: ['./gradlew check', 'npm test'], verifyTimeoutSeconds: 900 })));
  });
  it('says that builds stop as unverified when no checks are set', async () => {
    renderFactory();
    await open();
    expect(await screen.findByText('No checks: builds will stop as unverified.')).toBeInTheDocument();
  });
  it('shows the saved checks in the summary', async () => {
    vi.mocked(build.buildDefaults).mockResolvedValue(buildSetup({ revision: 2, baseBranch: 'main', harness: 'codex', model: 'TEST-model', verifyCommands: ['make test'], verifyTimeoutSeconds: 600 }));
    renderFactory();
    expect(await screen.findByText(/checks: make test/)).toBeInTheDocument();
  });
```
Update the existing exact-payload assertion at `:239-241`. It must include
`verifyCommands: [], verifyTimeoutSeconds: 1800`.

- [ ] **Step 2: Implement**

- **Form state:** add `verifyCommands: (defaults.verifyCommands ?? []).join('\n')` and
  `verifyTimeoutSeconds: String(defaults.verifyTimeoutSeconds ?? 1800)`.
- **Submit:** send
  `verifyCommands: form.verifyCommands.split('\n').map(line => line.trim()).filter(Boolean)` and
  `verifyTimeoutSeconds: Number(form.verifyTimeoutSeconds)`. Do not spread the raw string.
- **Fields:** after "Pay with", add:

```tsx
      <SettingField label="Check commands" scope="build setup" hint="One command per line, run in order in a clean copy of the build. The first failure stops the rest.">
        <textarea aria-label="Check commands" rows={4} value={form.verifyCommands} placeholder="./gradlew check"
          onChange={event => setForm(previous => ({ ...previous, verifyCommands: event.target.value }))} /></SettingField>
      {!form.verifyCommands.trim() && <p className="factory-note" role="status">No checks: builds will stop as unverified.</p>}
      <SettingField label="Verify time limit (seconds)" scope="build setup" hint={`For all checks together. At most ${choices.verifyMaxSeconds ?? 1800}.`}>
        <input aria-label="Verify time limit (seconds)" type="number" min={60} max={choices.verifyMaxSeconds ?? 1800} value={form.verifyTimeoutSeconds}
          onChange={event => setForm(previous => ({ ...previous, verifyTimeoutSeconds: event.target.value }))} /></SettingField>
```
- **`choices`:** keep `verifyMaxSeconds` from `buildOptions`.
- **Summary line:** append
  `` {defaults.verifyCommands?.length ? ` · checks: ${defaults.verifyCommands.join(', ')}` : ' · no checks'} ``.
- **`DecisionEvidence`:** add a `Checks` row to the facts list, showing
  `preparation.verifyCommands?.join(' → ') || 'none — builds stop as unverified'`.
- **CSS:** every new `className` must exist in `index.css`, or `styles.contract.test.ts` fails. These fields
  use existing classes only.

- [ ] **Step 3: Push and read CI** (`dashboard` job). Commit message: `Let the operator set a repository's checks`.

**Mutation guard:** the `.filter(Boolean)`. Expected to fail: `saves the check commands one per line and the verify time limit`.

---

### Task 14: Verify result on the work item screens (`spire-ui`)

**Files:**
- Modify: `spire-ui/src/components/work-items/workPreparationApi.ts` (`WorkExecution.verification`)
- Modify: `spire-ui/src/components/work-items/WorkItemSteps.tsx:95-104`
- Create: `spire-ui/src/components/work-items/VerifyResult.tsx`
- Modify: `spire-ui/src/components/work-items/DecisionPanel.tsx:60-96`
- Modify: `spire-ui/src/components/work-items/workReasons.ts`
- Modify: `spire-ui/src/index.css` (only if a new class is needed: `.verify-outcome.passed|failed|unverified`)
- Test: `WorkItemSteps.test.tsx`, `DecisionPanel.test.tsx`, `workReasons.test.ts`, `workJourney.test.ts`

**Interfaces — Produces (TS):**
```ts
export interface WorkVerification { attemptId: string; head: string; outcome: 'PASSED' | 'FAILED' | 'UNVERIFIED'; reason: string | null;
  checks: { command: string; exitCode: number | null; wallMillis: number; outputTail: string }[] }
// WorkExecution gains: verification?: WorkVerification | null
export function VerifyResult({ verification }: { verification: WorkVerification }): JSX.Element
export const isVerifyResultGate = (item: { reason: string; gate?: { phase: string; state: string } | null }) => boolean
```

- [ ] **Step 1: Failing tests**

```tsx
// WorkItemSteps.test.tsx
const verification = (outcome: 'PASSED' | 'FAILED' | 'UNVERIFIED', reason: string | null, exitCode: number | null) => ({
  attemptId: 'TEST-verify', head: 'b'.repeat(40), outcome, reason, checks: exitCode === null ? [] : [{ command: 'TEST-check', exitCode, wallMillis: 1200, outputTail: 'TEST-tail' }] });
const executionWith = (v: ReturnType<typeof verification>): WorkExecution => ({ runId: 'TEST-run',
  build: { workItemId: 'TEST-item', generation: 1, buildAttemptId: 'TEST-attempt', preparationBinding: 'a'.repeat(64) },
  head: 'b'.repeat(40), verificationAttempt: 'TEST-verify', verification: v, pullRequest: null, reviewId: null });

it('an unverified result never shows a tick', () => {
  show(item({ phase: 'verify', workflowStatus: 'waiting_approval', reason: 'verify_unverified', progress: { execution: executionWith(verification('UNVERIFIED', 'tool_missing', null)) } }));
  expect(step('Verify').getByText('Not checked')).toBeInTheDocument();
  expect(step('Verify').queryByLabelText('Passed')).toBeNull();
  expect(step('Verify').getByText(/A tool was probably missing/)).toBeInTheDocument();
});
it('a failed result names the command, its exit code, and opens its output', () => {
  show(item({ phase: 'verify', workflowStatus: 'waiting_approval', reason: 'verify_failed', progress: { execution: executionWith(verification('FAILED', 'check_failed', 2)) } }));
  expect(step('Verify').getByText('Failed')).toBeInTheDocument();
  fireEvent.click(step('Verify').getByRole('button', { name: /TEST-check/ }));
  expect(step('Verify').getByText('TEST-tail')).toBeInTheDocument();
});
it('a passed result shows the tick', () => {
  show(item({ phase: 'deliver', workflowStatus: 'active', reason: 'phase_started', progress: { execution: executionWith(verification('PASSED', null, 0)) } }));
  expect(step('Verify').getByLabelText('Passed')).toBeInTheDocument();
});

// DecisionPanel.test.tsx
it('a verify result gate offers Retry build and Stop', async () => {
  vi.mocked(api.approvals).mockResolvedValue([{ ...row, gate: { ...row.gate, phase: 'verify' } }]);
  vi.mocked(gateway.getWorkItem).mockResolvedValue({ ...item, phase: 'verify', reason: 'verify_failed' });
  show();
  expect(await screen.findByRole('button', { name: 'Retry build' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Stop' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
});
it('Retry build answers approve, and Stop answers reject', async () => {
  const resultGate = { ...row, gate: { ...row.gate, phase: 'verify' } };
  vi.mocked(api.approvals).mockResolvedValue([resultGate]);
  vi.mocked(gateway.getWorkItem).mockResolvedValue({ ...item, phase: 'verify', reason: 'verify_failed' });
  const decided = vi.fn();
  show(decided);   // the existing show() helper, passing onDecided as the answer test at :73 does
  fireEvent.click(await screen.findByRole('button', { name: 'Retry build' }));
  await waitFor(() => expect(decided).toHaveBeenCalledWith('Retrying the build.'));
  expect(api.answer).toHaveBeenCalledWith(resultGate.gate, expect.any(String), true, expect.any(String));
  cleanup();
  show(decided);
  fireEvent.click(await screen.findByRole('button', { name: 'Stop' }));
  await waitFor(() => expect(decided).toHaveBeenCalledWith('Stopped after verification.'));
  expect(api.answer).toHaveBeenLastCalledWith(resultGate.gate, expect.any(String), false, expect.any(String));
});

// workJourney.test.ts
it('a verify result gate is in Needs you', () => {
  expect(filterById('needs-you').statuses).toContain('waiting_approval');
  expect(nextAction(row({ workflowStatus: 'waiting_approval', reason: 'verify_failed', gate: { ...gate, phase: 'verify', state: 'OPEN' } }))?.label).toBe('Review the verify decision');
});

// workReasons.test.ts
it.each(['verify_failed', 'verify_unverified', 'verify_stopped_by_operator', 'check_failed', 'no_checks_declared', 'tool_missing', 'timed_out', 'checkpoint_missing', 'verify_could_not_run'])('%s has a sentence', reason => {
  expect(workReason(reason)).not.toBe(reason);
});
```

- [ ] **Step 2: Implement**

**`workReasons.ts`** (add to `REASONS`):
```ts
  verify_failed: 'A check failed on this build. Retry the build with the failure, or stop.',
  verify_unverified: 'The checks could not run, so this build is not verified. It is not a pass.',
  verify_stopped_by_operator: 'Stopped after verification, by an operator.',
  check_failed: 'A check command exited with an error.',
  no_checks_declared: 'No checks are set for this repository, so nothing was verified.',
  tool_missing: 'A tool was probably missing from the agent image (exit code 126 or 127).',
  timed_out: 'The checks did not finish within the verify time limit.',
  checkpoint_missing: "The build's commits could not be read for verification.",
  verify_could_not_run: 'Verification could not run.',
```
Replace the `verify_capability_unavailable` sentence with:
`'Verification is not available for this item.'`.

**`VerifyResult.tsx`:**
```tsx
import { useState } from 'react';
import { Check, ChevronDown, ChevronRight, CircleHelp, X } from 'lucide-react';
import type { WorkVerification } from './workPreparationApi';
import { workReason } from './workReasons';

const LOOK = {
  PASSED: { label: 'Passed', tone: 'passed', icon: <Check size={14} aria-hidden="true" /> },
  FAILED: { label: 'Failed', tone: 'failed', icon: <X size={14} aria-hidden="true" /> },
  UNVERIFIED: { label: 'Not checked', tone: 'refused', icon: <CircleHelp size={14} aria-hidden="true" /> },
} as const;

/** Three looks that cannot be confused: only PASSED ever carries a tick (spec §5). */
export function VerifyResult({ verification }: { verification: WorkVerification }) {
  const look = LOOK[verification.outcome];
  const [open, setOpen] = useState<number | null>(null);
  return <div>
    <p className="factory-note"><span className={`pill ${look.tone}`} aria-label={look.label}>{look.icon} {look.label}</span>
      {verification.reason && <> {workReason(verification.reason)}</>}</p>
    {verification.checks.length > 0 && <table className="prov-table" aria-label="Checks">
      <thead><tr><th>Command</th><th>Exit</th><th>Time</th></tr></thead>
      <tbody>{verification.checks.map((check, index) => <tr key={index}>
        <td><button className="ctx-item-head" type="button" aria-expanded={open === index} onClick={() => setOpen(open === index ? null : index)}>
          {open === index ? <ChevronDown size={14} /> : <ChevronRight size={14} />}<code>{check.command}</code></button>
          {open === index && <pre className="ctx-detail">{check.outputTail || '(no output)'}</pre>}</td>
        <td className="mono">{check.exitCode ?? 'did not run'}</td>
        <td className="mono">{(check.wallMillis / 1000).toFixed(1)} s</td></tr>)}</tbody>
    </table>}
  </div>;
}
```
`aria-label={look.label}` on the pill is the only accessible name `Passed`. `getByText('Not checked')` reads
the text node.

**`WorkItemSteps.tsx`.** Replace the verify line in `Delivery`:
```tsx
  if (phase === 'verify') return execution.verification
    ? <VerifyResult verification={execution.verification} />
    : <p className="factory-note">{execution.verificationAttempt ? 'Verification recorded without a result' : 'Verification not recorded'}</p>;
```

**`DecisionPanel.tsx`.**
- `const resultGate = gate?.phase === 'verify' && ['verify_failed', 'verify_unverified'].includes(detail?.reason ?? '');`
  uses the work item the panel already loads with `getWorkItem`. Read the component to find the variable
  name.
- The heading is `resultGate ? 'Verification did not pass' : ...`.
- The buttons are `resultGate ? 'Retry build' / 'Stop' : 'Approve' / 'Reject'`. The busy labels are
  `Retrying…` and `Stopping…`.
- The notices are `resultGate ? (approve ? 'Retrying the build.' : 'Stopped after verification.') : ...`.
- Show `<VerifyResult verification={detail.progress.execution.verification} />` above the buttons when it
  is present.
- In `DecisionEvidence`, the "If you approve" text for a result gate is: *"A new build starts from this
  build's last commit, with the failure in its prompt. It counts against this item's run limit."*

The binding check needs no exemption. The verify gate's `artifact` is the preparation binding, as for plan
gates (`WorkGate.artifactOf`).

- [ ] **Step 3: Push and read CI** (`dashboard`). Commit message: `Show verification results and the retry decision`.

**Mutation guards:**
1. `UNVERIFIED.icon` set to `<Check/>`, with `aria-label` `Passed`. Expected to fail:
   `an unverified result never shows a tick`.
2. `resultGate` forced to `false`. Expected to fail: `a verify result gate offers Retry build and Stop`.

---

### Task 15: The agent image contract checks `/bin/sh` and uid 1001 (`spire-agent-image`)

**Files:**
- Modify: `spire-agent-image/src/main/java/dev/codespire/agentimage/Clauses.java`, `AgentImageVerifier.java`
- Modify: `docs/factory/AGENT-IMAGE-CONTRACT.md`
- Test: `AgentImageVerifierTest.java`, `ConformanceReportTest.java`

- [ ] **Step 1: Failing tests** (using the fake `ImageProbe` the test already has)

```java
    @Test void anImageWithoutAShellFailsTheShellClause(){
        var report=verifierAnswering(conformingProbeLines().replace("shell=yes","shell=no")).verify("TEST-image");
        assertFalse(report.clause(Clauses.SHELL).passed());assertTrue(report.clause(Clauses.UID_1001).passed());
    }
    @Test void anImageNotRunningAsUid1001FailsTheUidClause(){
        var report=verifierAnswering(conformingProbeLines().replace("uid=1001","uid=1000")).verify("TEST-image");
        assertFalse(report.clause(Clauses.UID_1001).passed());assertTrue(report.clause(Clauses.SHELL).passed());
    }
```
`verifierAnswering(String lines)` and `conformingProbeLines()` stand for the test's existing fake-probe
builder and its "everything conforms" answer. Read `AgentImageVerifierTest` and use its real helper names.
Add `shell=yes` and `uid=1001` to the conforming answer, and use the report's real accessor for one clause
in place of `clause(...)`.

- [ ] **Step 2: Implement**

- Add `SHELL` and `UID_1001` to `Clauses` and to `VERIFIED`.
- In `runtimeProbeScript()`, add:
  - `echo shell=$([ -x /bin/sh ] && echo yes || echo no)`
  - `echo uid=$(id -u)`
- The checks:
  - `SHELL` passes on `shell=yes`.
  - `UID_1001` passes on `uid=1001`. Its failure text: *"verify volumes are written by the publisher as
    uid 1001; a check running as another uid cannot write its build output"*.
- In `AGENT-IMAGE-CONTRACT.md`, add both clauses to the verified table, with the M4 reason.

- [ ] **Step 3: Push and read CI.** Commit message: `Check the shell and uid that verify containers need`.

**Mutation guard:** `UID_1001` comparing against `"1000"`. Expected to fail: `anImageNotRunningAsUid1001FailsTheUidClause`.

---

### Task 16: Documentation

**Files:**
- Modify: `docs/factory/PRD.md` (FR-F20)
- Modify: `docs/DECISIONS.md` (new ADR-046; correct ADR-033's phase order to verify → deliver → review)
- Modify: `docs/factory/RUN-TOPOLOGY.md` (a "Verify unit" section)
- Modify: `docs/UNVERIFIED.md` (new rows)
- Modify: `docs/factory/ROADMAP.md` (the M4 section: "slice 1, verify, implemented; live proof pending")
- Modify: `docs/factory/ARCHITECTURE.md:36-37,263-264` and `docs/DECISIONS.md:11` ("VERIFY unavailable" sentences)

- [ ] **Step 1: FR-F20.** Replace "repository-declared back-pressure" with: *"repository-owned checks,
  declared by the operator in the repository's build setup (M4). A repository-declared file is a later
  option (H1: the repository proposes, the operator pins). Rationale: ADR-046."*

- [ ] **Step 2: ADR-046.** Write it in the format of ADR-045. It has these parts:
  - **Context:** the three M3.5 runs stopped at verify, plus FR-F20.
  - **Decision:**
    - operator-declared commands, copied into the preparation and bound by version 5;
    - run on a clean copy of the checkpoint commit;
    - a verify unit of a publisher-image prepare plus agent-image checks, with no credentials;
    - three outcomes;
    - a result gate in every mode but `off` (the one exception to `auto`), with Retry build or Stop.
  - **Consequences:**
    - one ack window bounds verify;
    - a retry counts against `maxRunsPerItem`;
    - the Docker arm enforces no egress.
  - **Rejected:**
    - B, a repository file (ADR-036), together with the prior-art list from spec §2;
    - verifying in the kept workspace (loose files; the ADR-039 hooks attack);
    - automatic retries in this slice (the plan coordinator owns them).

- [ ] **Step 3: ADR-033.** Change the phase list to `verify, deliver, review, land`. Add one sentence saying
  that the code and ADR-045 have always run deliver before review.

- [ ] **Step 4: `UNVERIFIED.md`.** Add these rows, with the evidence each needs:
  - verify has no live proof yet;
  - the Docker arm gives verify unrestricted network;
  - `tool_missing` is inferred from exit codes 126 and 127;
  - the `/bin/sh` and uid clauses are Mode S until run against the reference image.

  Also update the row that said "verification executors remain M4".

- [ ] **Step 5: Push and read CI** (docs only: the gitleaks and Semgrep jobs still run). Commit message:
  `Record the verify decision and its limits`.

---

### Task 17: Live proof and acceptance (needs the operator)

**Files:**
- Create: `docs/factory/M4-VERIFY-ACCEPTANCE.md`
- Modify: `docs/HISTORY.md`, `CLAUDE.md` (Status), `docs/UNVERIFIED.md` (close the "no live proof" row)

This task needs the operator. Its steps are run with them on the dev stack, after the PR is green and
merged (or the dev stack runs the branch).

- [ ] **Step 1: Rebuild the images and the stack from this tree** (CLAUDE.md gotcha: dev images bake their
  source):

```bash
./gradlew :spire-publisher:installDist && docker build -t spire-publisher:latest spire-publisher
docker compose -p spire-dev -f docker-compose.yml -f docker-compose.dev.yml -f docker-compose.idp.yml -f docker-compose.auth.yml up -d --build
```
The run worker runs through `quarkusDev`; restart it from this tree. These are the user's own dev stack
commands; the no-local-builds rule allows starting a stack the user asked for. Confirm with the user before
running them.

- [ ] **Step 2: Ticket 1 — passed.** The operator sets the checks on `spire-test` to commands that pass
  against its tree. Pick them by reading the repository. If it has no build, a check like `test -f README.md`
  is honest, but say so in the record. The operator writes the ticket, labels it, and approves the plan. The
  expected result:
  - verify is passed;
  - the deliver phase opens a pull request;
  - the reviewer reviews it.

  Record the work item id, the gate ids, the run id, the verify attempt id, the PR URL and the reviewer's
  review id.

- [ ] **Step 3: Ticket 2 — failed, then retry.** Use a check that fails on the first build: a check the
  ticket's change makes pass only when done correctly, or one the operator chooses. The steps:
  - the verify gate shows the command and its output tail;
  - the operator picks **Retry build**;
  - the new run's command shows `startFromRunId`, and its prompt has the failure;
  - the retry passes.

- [ ] **Step 4: Ticket 3 — tool missing.** A check that names a tool the agent image lacks (for example
  `cargo --version`) gives unverified `tool_missing`, a gate, and no tick on any screen. Take a screenshot.

- [ ] **Step 5: Write `M4-VERIFY-ACCEPTANCE.md`.** Use the M3.5 record's layout: the runs table, the
  identifiers, what each run proves, what the proof found, and the limits that remain. Append a
  `HISTORY.md` entry, rewrite the `CLAUDE.md` Status snapshot, and update `UNVERIFIED.md`.

- [ ] **Step 6: Push and read CI.** Commit message: `Record M4 verify as accepted, with its live proof`.

---

## Known gaps this plan does not close (state them in the PR body)

- **The superseded run's dashboard status.** After a retry, the superseded build's `factory_run` row stays
  `awaiting_delivery` in the dashboard, although the run worker has retired its unit. A later slice should
  add a `superseded` status.
- **The hold outbox.** It is not fed from `work_verify_effect`. A takeover reaches a running verify through
  the build run's existing hold row, because the verify containers share the build's run id and `cancel`
  kills role `verify` (Task 8). This is simpler than spec §4.3's wording, and has the same effect.
- **Mutation checks.** They cost one CI run per mutant. Batch them per task, as described under Global
  Constraints.
