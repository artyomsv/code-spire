package dev.codespire.contract.scm;

/**
 * OPAQUE conversation-thread handle: a comment id on Bitbucket/GitHub/DC, a
 * {@code discussion_id} on GitLab (SCM-MAPPING.md §6). Never interpret the value.
 */
public record ThreadRef(String value) {
}
