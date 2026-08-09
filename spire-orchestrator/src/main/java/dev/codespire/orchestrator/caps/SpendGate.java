package dev.codespire.orchestrator.caps;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
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
     * Whether deployment-wide usage over the policy's rolling window exceeds either configured cap.
     * Both axes are checked because a money-only cap is inert by design on an {@code UNMETERED}
     * deployment — every charge there is a legitimate zero, so {@code SUM(cost_millicents)} never
     * approaches a positive limit, and the call count is what actually bounds an unpriced fleet
     * (ADR-023).
     *
     * <p>Skips the ledger read entirely when neither limit is set — unset must be a true no-op, not a
     * query that always answers "allow".
     */
    public CapRefusal decide() {
        OptionalLong spendCap = policy.spendCapMillicents();
        OptionalInt callCap = policy.callCap();
        if (spendCap.isEmpty() && callCap.isEmpty()) {
            return CapRefusal.allow();
        }
        SpendWindow.Usage usage = window.since(Instant.now().minus(policy.window()));
        if (spendCap.isPresent() && usage.spentMillicents() >= spendCap.getAsLong()) {
            return CapRefusal.spendCapReached(usage.spentMillicents(), spendCap.getAsLong());
        }
        if (callCap.isPresent() && usage.calls() >= callCap.getAsInt()) {
            return CapRefusal.callCapReached(usage.calls(), callCap.getAsInt());
        }
        return CapRefusal.allow();
    }
}
