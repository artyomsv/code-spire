package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.work.WorkVerification;
import dev.codespire.runtime.VerifyRun;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place a verify unit's observations become an outcome (spec §3.2). It never infers PASSED: passed
 * needs the asked head prepared and every declared command run to exit 0.
 */
final class VerifyOutcomes {
    private static final int NOT_EXECUTABLE = 126, NOT_FOUND = 127;
    /**
     * A passing check's output is context, not evidence: its last lines only. The check that decided the
     * outcome keeps its full tail. Twenty full tails would be 1.3 MB, over Kafka's 1 MiB record default, and a
     * result the broker refuses is resent for ever.
     */
    static final int PASSING_TAIL_CHARS = 2 * 1024;

    private VerifyOutcomes() {
    }

    static WorkVerification classify(RunCommand.VerifyWork command, VerifyRun run) {
        List<WorkVerification.CheckResult> checks = new ArrayList<>();
        for (int i = 0; i < run.checks().size(); i++) {
            VerifyRun.Check check = run.checks().get(i);
            String tail = String.join("\n", check.tail());
            int cap = Integer.valueOf(0).equals(check.exitCode()) ? PASSING_TAIL_CHARS : WorkVerification.MAX_TAIL_CHARS;
            if (tail.length() > cap) tail = tail.substring(tail.length() - cap);
            checks.add(new WorkVerification.CheckResult(command.commands().get(i), check.exitCode(), check.wallMillis(), tail));
        }
        String reason = reason(command, run);
        WorkVerification.Outcome outcome = reason == null ? WorkVerification.Outcome.PASSED
                : "check_failed".equals(reason) ? WorkVerification.Outcome.FAILED : WorkVerification.Outcome.UNVERIFIED;
        return new WorkVerification(command.attemptId(), command.head(), outcome, reason, checks);
    }

    /** Null means passed. Ordered so a cause that makes later observations meaningless is named first. */
    private static String reason(RunCommand.VerifyWork command, VerifyRun run) {
        if (command.commands().isEmpty()) return "no_checks_declared";
        if (run.prepare() == VerifyRun.Prepare.CHECKPOINT_MISSING) return "checkpoint_missing";
        if (run.prepare() == VerifyRun.Prepare.FAILED) return run.timedOut() ? "timed_out" : "verify_could_not_run";
        // The prepared tree is the one a pass would vouch for; any other head is not this build.
        if (!command.head().equals(run.preparedHead())) return "checkpoint_missing";
        if (run.timedOut()) return "timed_out";
        for (VerifyRun.Check check : run.checks()) {
            if (check.exitCode() == null) return "verify_could_not_run";
            // Inferred, not proven: the shell's codes for "cannot run" and "not found", which a suite could also use.
            if (check.exitCode() == NOT_EXECUTABLE || check.exitCode() == NOT_FOUND) return "tool_missing";
            if (check.exitCode() != 0) return "check_failed";
        }
        return run.checks().size() == command.commands().size() ? null : "verify_could_not_run";
    }
}
