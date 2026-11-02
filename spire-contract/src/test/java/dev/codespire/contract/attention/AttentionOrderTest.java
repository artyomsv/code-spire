package dev.codespire.contract.attention;

import dev.codespire.contract.attention.AttentionView.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one order the attention bell is shown in, shared by the orchestrator's feed and the gateway's: blockers first,
 * then problems about a single review, then the newest, then code and subject. A just-refused review used to sit at the
 * bottom of 23 rows because the order was severity, then code A–Z.
 *
 * <p>Each case is built so exactly one comparator step decides it, and the code tie-break alone would decide it the
 * other way — so removing that step fails that case and no other.
 */
class AttentionOrderTest {

    private static final Instant OLDER = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void aBlockerComesBeforeANewerReviewWarning() {
        AttentionView blocker = row("Z_BLOCKER", Severity.BLOCKING, null, OLDER);
        AttentionView review = row("REVIEW_FAILED", Severity.WARNING, "TEST-WS/TEST-REPO#1", NEWER);

        assertEquals(List.of(blocker, review), sorted(review, blocker));
    }

    @Test
    void aReviewWarningComesBeforeANewerWarningAboutSomethingElse() {
        AttentionView review = row("REVIEW_HARNESS_REFUSED", Severity.WARNING, "TEST-WS/TEST-REPO#95", OLDER);
        AttentionView deadLetter = row("DLQ_PENDING", Severity.WARNING, null, NEWER);

        assertEquals(List.of(review, deadLetter), sorted(deadLetter, review));
    }

    @Test
    void theNewerRowComesFirstWithinAGroup() {
        AttentionView older = row("A_CONDITION", Severity.WARNING, null, OLDER);
        AttentionView newer = row("Z_CONDITION", Severity.WARNING, null, NEWER);

        assertEquals(List.of(newer, older), sorted(older, newer));
    }

    @Test
    void aRowWithNoKnownTimeComesAfterTimedRows() {
        AttentionView untimed = row("A_CONDITION", Severity.WARNING, null, null);
        AttentionView timed = row("Z_CONDITION", Severity.WARNING, null, OLDER);

        assertEquals(List.of(timed, untimed), sorted(untimed, timed));
    }

    @Test
    void equalTimesFallBackToCodeThenSubject() {
        AttentionView laterCode = row("B_CONDITION", Severity.WARNING, "a", OLDER);
        AttentionView earlierCode = row("A_CONDITION", Severity.WARNING, "z", OLDER);
        AttentionView subjectB = row("C_CONDITION", Severity.WARNING, "b", OLDER);
        AttentionView subjectA = row("C_CONDITION", Severity.WARNING, "a", OLDER);
        AttentionView noSubject = row("C_CONDITION", Severity.WARNING, null, OLDER);

        assertEquals(List.of(earlierCode, laterCode, noSubject, subjectA, subjectB),
                sorted(subjectB, laterCode, subjectA, earlierCode, noSubject));
    }

    /** "REVIEW_" is a prefix of the review codes only: a repository row is not about a review. */
    @Test
    void onlyReviewCodesAreReviewRows() {
        assertTrue(AttentionOrder.isReviewRow(row("REVIEW_STUCK", Severity.WARNING, null, null)));
        assertTrue(AttentionOrder.isReviewRow(row("REVIEW_HARNESS_REFUSED", Severity.WARNING, null, null)));
        assertFalse(AttentionOrder.isReviewRow(row("REPOSITORY_NOT_REGISTERED", Severity.WARNING, null, null)));
        assertFalse(AttentionOrder.isReviewRow(row("RUN_PUSH_GATE_REFUSED", Severity.WARNING, null, null)));
    }

    /** The shorter constructors stay valid and carry no time; the wither keeps every other component. */
    @Test
    void theWitherKeepsEveryOtherComponent() {
        AttentionView plain = new AttentionView("REVIEW_FAILED", Severity.WARNING, "s", "m", "/a", "/d");

        AttentionView timed = plain.withSince(NEWER);

        assertEquals(null, plain.since());
        assertEquals(new AttentionView("REVIEW_FAILED", Severity.WARNING, "s", "m", "/a", "/d", NEWER), timed);
    }

    private static AttentionView row(String code, Severity severity, String subject, Instant since) {
        return new AttentionView(code, severity, subject, "message", null, null, since);
    }

    private static List<AttentionView> sorted(AttentionView... rows) {
        List<AttentionView> list = new ArrayList<>(List.of(rows));
        list.sort(AttentionOrder.ORDER);
        return list;
    }
}
