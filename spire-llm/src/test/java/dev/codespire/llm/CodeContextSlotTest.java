package dev.codespire.llm;

import dev.codespire.contract.review.ContextItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeContextSlotTest {

    private static String bodyOf(List<ContextItem> context) {
        return ReviewPromptBuilder.build(TestFixtures.pr(), TestFixtures.patches(), context)
                .prompt().user();
    }

    @Test
    void codeSnippetsRenderIntoTheirOwnSlotAndTicketsIntoTheirs() {
        String user = bodyOf(List.of(
                new ContextItem("JIRA_TICKET", "CANARY-1", "ticket body text", "https://example.invalid/1"),
                new ContextItem("CODE_SNIPPET", "chargeFor — src/Pricer.java", "long chargeFor()", "src/Pricer.java")));

        assertTrue(user.contains("ticket body text"));
        assertTrue(user.contains("long chargeFor()"));
    }

    @Test
    void anOversizedTicketCannotEvictCodeSnippets() {
        String huge = "x".repeat(200_000);
        String user = bodyOf(List.of(
                new ContextItem("JIRA_TICKET", "CANARY-1", huge, "https://example.invalid/1"),
                new ContextItem("CODE_SNIPPET", "chargeFor — src/Pricer.java", "long chargeFor()", "src/Pricer.java")));

        // The slots are budgeted independently. Sharing one slot is what would make this fail,
        // silently, on exactly the repositories with the richest ticket context.
        assertTrue(user.contains("long chargeFor()"));
    }

    @Test
    void anOversizedSnippetSetCannotEvictTicketContext() {
        String huge = "y".repeat(200_000);
        String user = bodyOf(List.of(
                new ContextItem("JIRA_TICKET", "CANARY-1", "ticket body text", "https://example.invalid/1"),
                new ContextItem("CODE_SNIPPET", "big — src/Big.java", huge, "src/Big.java")));

        assertTrue(user.contains("ticket body text"));
    }
}
