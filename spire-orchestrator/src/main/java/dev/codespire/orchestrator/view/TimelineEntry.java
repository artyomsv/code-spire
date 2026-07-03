package dev.codespire.orchestrator.view;

import java.time.Instant;

/** One row of the live event timeline. lane: integration | command | domain | result. */
public record TimelineEntry(long seq, String lane, String type, String reviewId, String detail, Instant at) {
}
