package dev.codespire.contract.scm;

/** A posted comment: its id, the thread it belongs to, and its kind. */
public record CommentRef(String commentId, ThreadRef thread, CommentKind kind) {
}
