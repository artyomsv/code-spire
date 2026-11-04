package dev.codespire.orchestrator.pipeline;

import dev.codespire.orchestrator.review.run.HarnessReplyStart;

/**
 * A repository that reviews with an API provider, so every reply takes the API path: the real start reads the
 * repository's setting from the database, which a plain unit test must never open (CLAUDE.md, saga test fakes).
 */
final class NoHarnessReplies extends HarnessReplyStart {

    @Override
    public Decision start(ReplyAsk ask) {
        return new NotHarness();
    }
}
