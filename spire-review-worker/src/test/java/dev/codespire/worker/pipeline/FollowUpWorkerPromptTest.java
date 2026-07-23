package dev.codespire.worker.pipeline;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.scm.CommentKind;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Confirms the command-carried follow-up template (Task 5/6) actually reaches the LLM call. */
class FollowUpWorkerPromptTest {

    @Test
    void customTemplatePersonaReachesThePrompt() {
        DiffSource diffs = mock(DiffSource.class);
        CommentSink sink = mock(CommentSink.class);
        LlmProvider llm = mock(LlmProvider.class);
        ModelParams params = new ModelParams("m", 0.2, null);
        RepoRef repo = new RepoRef("ws", "slug");
        ThreadRef thread = new ThreadRef("t");
        ThreadTranscript transcript = new ThreadTranscript(thread, "a.java", 3, "sha",
                List.of(new ThreadMessage("dev", "why?", false)));
        PromptTemplate custom = new PromptTemplate(PromptKind.FOLLOWUP,
                "CUSTOM-FOLLOWUP-PERSONA", "Answer {{thread}}");

        when(diffs.fetchDiff(eq(repo), eq(1L), eq("sha")))
                .thenReturn(new Diff(DiffRefs.headOnly("sha"), List.of(), false));
        when(llm.complete(any(), any())).thenReturn(CompletableFuture.completedFuture(
                new Completion("ok", new ModelUsage("m", 1, 1, 0))));
        when(sink.replyInThread(eq(repo), eq(1L), eq(thread), anyString()))
                .thenReturn(new CommentRef("c-1", thread, CommentKind.REPLY));

        FollowUpWorker.answer(repo, 1L, thread, transcript, diffs, llm, params, sink, custom);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(llm).complete(captor.capture(), eq(params));
        assertTrue(captor.getValue().system().contains("CUSTOM-FOLLOWUP-PERSONA"));
    }
}
