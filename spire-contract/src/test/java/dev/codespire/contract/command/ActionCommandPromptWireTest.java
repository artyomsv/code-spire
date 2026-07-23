package dev.codespire.contract.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActionCommandPromptWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generateReviewRoundTripsPromptTemplates() throws Exception {
        PromptTemplate review = new PromptTemplate(PromptKind.REVIEW, "sys", "body {{diff}}");
        GenerateReview cmd = new GenerateReview("r-1", new RepoRef("ws", "slug"), 5L, "sha",
                "ctx", 1, null, null, null, null, review, null);
        String json = mapper.writeValueAsString(cmd);
        GenerateReview back = (GenerateReview) mapper.readValue(json, ActionCommand.class);
        assertEquals("body {{diff}}", back.reviewPrompt().body());
        assertNull(back.reconcilePrompt());
    }

    @Test
    void legacyConstructorLeavesPromptsNull() {
        GenerateReview cmd = new GenerateReview("r-1", new RepoRef("ws", "slug"), 5L, "sha",
                "ctx", 1, null, null, null);
        assertNull(cmd.reviewPrompt());
        assertNull(cmd.reconcilePrompt());
    }
}
