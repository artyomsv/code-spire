package dev.codespire.worksource;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkSnapshotTest {
    private static final WorkIssueRef ISSUE = new WorkIssueRef(WorkSourceType.GITHUB,
            "https://github.example.test", "TEST-project-id", "TEST-issue-id");

    @Test void aCallerCannotRewriteTheObservedCurrentLabels() {
        Set<String> providerLabels = new HashSet<>(Set.of("TEST-suggest"));
        WorkTicket ticket = new WorkTicket(new WorkIssueLocation(ISSUE, "TEST-1", URI.create("https://github.example.test/TEST/repo/issues/1")),
                "TEST-title", "TEST-body", "open", providerLabels);
        providerLabels.clear();
        assertEquals(Set.of("TEST-suggest"), ticket.currentLabels());
        assertThrows(UnsupportedOperationException.class, () -> ticket.currentLabels().clear());
    }

    @Test void aCallerCannotRewriteAnAuditPageAfterItWasObserved() {
        List<WorkIssueRef> providerItems = new ArrayList<>(List.of(ISSUE));
        WorkPage<WorkIssueRef> page = new WorkPage<>(providerItems, "TEST-next");
        providerItems.clear();
        assertEquals(List.of(ISSUE), page.items());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }
}
