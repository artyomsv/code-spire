package dev.codespire.orchestrator.caps;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * "May a paid LLM call be made right now?" — asked immediately before every one of them.
 *
 * <p>One bean rather than one check per call site, because the two sites are the review pipeline and the
 * conversation pipeline: different sagas, different triggers, the same question. Two copies of this
 * comparison would be free to drift — one gaining an axis, one keeping {@code >} where the other has
 * {@code >=} — and drift in a money gate is invisible until it fails to fire. Sharing the decision makes
 * "both sites reach the same verdict from the same inputs" true by construction rather than by test.
 *
 * <p>The policy is read fresh on every call (the posture {@link CapPolicy} sets), so raising a cap in
 * Settings takes effect without a restart.
 */
@ApplicationScoped
public class SpendGate {

    @Inject
    CapPolicy policy;

    @Inject
    SpendWindow window;

    /**
     * The gate's verdict, plus whether it could actually see the ledger it judged on.
     *
     * <p>Two facts rather than one because they have different audiences and different consequences: the
     * refusal decides whether a call happens, while {@code ledgerUnreadable} decides whether anyone is
     * told the cap has stopped enforcing. Folding the second into the first would mean either refusing
     * on a failed read (an outage that looks like policy) or discarding it (the silence this exists to
     * remove). Carried on the one call so the enforcement sites and the attention row keep reaching the
     * same verdict from the same inputs, which is the whole reason this bean exists.
     *
     * <p>{@code ledgerUnreadable} is false when no cap is configured: nothing is read, so nothing failed
     * — there is no enforcement to be degraded.
     */
    public record Decision(CapRefusal refusal, boolean ledgerUnreadable) {

        public static Decision of(CapRefusal refusal) {
            return new Decision(refusal, false);
        }

        /** Fail open, and say so: allowed, but on a figure nobody could read. */
        public static Decision degraded() {
            return new Decision(CapRefusal.allow(), true);
        }

        public boolean refused() {
            return refusal.refused();
        }

        public boolean allowed() {
            return refusal.allowed();
        }
    }

    /**
     * Whether deployment-wide usage over the policy's rolling window exceeds either configured cap.
     * Both axes are checked because a money-only cap is inert by design on an {@code UNMETERED}
     * deployment — every charge there is a legitimate zero, so {@code SUM(cost_millicents)} never
     * approaches a positive limit, and the call count is what actually bounds an unpriced fleet
     * (ADR-023).
     *
     * <p>Skips the ledger read entirely when neither limit is set — unset must be a true no-op, not a
     * query that always answers "allow".
     *
     * <p><b>Both axes refuse on {@code >=}, and the diff gate refuses on {@code >}, deliberately.</b> A
     * budget is exhausted when it has been consumed: reaching a 100-call cap means 100 calls have been
     * paid for, so the 101st must not happen. A size <em>limit</em> is a ceiling a diff may reach — a
     * 100-file limit says a 100-file diff is reviewable. The asymmetry is stated because this bean's
     * reason for existing is that two copies of a money comparison are free to drift, and an undocumented
     * difference between the two gates is indistinguishable from exactly that drift.
     */
    public Decision decide() {
        OptionalLong spendCap = policy.spendCapMillicents();
        OptionalInt callCap = policy.callCap();
        if (spendCap.isEmpty() && callCap.isEmpty()) {
            return Decision.of(CapRefusal.allow());
        }
        Optional<SpendWindow.Usage> read = window.since(Instant.now().minus(policy.window()));
        if (read.isEmpty()) {
            return Decision.degraded();
        }
        SpendWindow.Usage usage = read.get();
        if (spendCap.isPresent() && usage.spentMillicents() >= spendCap.getAsLong()) {
            return Decision.of(CapRefusal.spendCapReached(usage.spentMillicents(), spendCap.getAsLong()));
        }
        if (callCap.isPresent() && usage.calls() >= callCap.getAsInt()) {
            return Decision.of(CapRefusal.callCapReached(usage.calls(), callCap.getAsInt()));
        }
        return Decision.of(CapRefusal.allow());
    }
}
