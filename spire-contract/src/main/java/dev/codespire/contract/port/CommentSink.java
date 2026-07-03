package dev.codespire.contract.port;

import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.CommentRef;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;

/**
 * SCM write adapter (CONTRACT §7). Thread-reply and PR-author are FIRST-CLASS
 * (unimplemented in PR-Agent's Bitbucket providers — a founding gap). DiffRefs
 * feeds GitLab/GitHub anchoring; ThreadRef is a comment id (BB/GH/DC) or a
 * discussion_id (GitLab). See SCM-MAPPING.md.
 */
public interface CommentSink {

    ScmType type();

    CommentRef postSummary(RepoRef repo, long prId, String bodyMd);

    CommentRef postInline(RepoRef repo, long prId, DiffRefs refs, InlineAnchor anchor, String bodyMd);

    CommentRef replyInThread(RepoRef repo, long prId, ThreadRef thread, String bodyMd);

    Author getPullRequestAuthor(RepoRef repo, long prId);
}
