package dev.codespire.workspace;

/**
 * What happened to a path between the base commit and the agent's head.
 *
 * <p>A rename is reported as BOTH {@link #RENAMED_FROM} and {@link #RENAMED_TO}, because the push
 * gate has to be able to refuse either side: a rename INTO a protected path is the obvious evasion
 * of a gate that only asks where a file came from, and a rename OUT of one deletes it.
 */
public enum ChangeKind { ADDED, MODIFIED, DELETED, RENAMED_FROM, RENAMED_TO }
