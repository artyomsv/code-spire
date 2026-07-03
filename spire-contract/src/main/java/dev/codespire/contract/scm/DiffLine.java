package dev.codespire.contract.scm;

/**
 * One diff line carrying BOTH old and new line numbers — this is what makes
 * inline anchoring work on every provider (SCM-MAPPING.md). {@code oldLine} is
 * null for ADDED lines; {@code newLine} is null for REMOVED lines.
 */
public record DiffLine(LineType type, Integer oldLine, Integer newLine, String content) {
}
