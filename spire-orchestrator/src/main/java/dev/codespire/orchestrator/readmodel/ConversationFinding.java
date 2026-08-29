package dev.codespire.orchestrator.readmodel;

/**
 * A finding a human filed with {@code /finding}, on its way into the corpus (P4 / ADR-027).
 *
 * <p>A record rather than five positional arguments, because four of them are strings and ints in a
 * row — the shape where a transposed pair compiles and fails only at runtime. This project has
 * already been bitten by exactly that on a two-{@code Set} constructor.
 *
 * <p>It carries no message, deliberately: {@code ConversationFindingRaised} omits one because a
 * quoted snippet must never enter the replayable event log (DATA-MODEL §5), so the column stays null
 * rather than being invented here.
 */
public record ConversationFinding(String commit, String path, int line, String severity,
                                  String threadRef) {
}
