package dev.codespire.contract.review;

/**
 * One review finding. {@code message}/{@code suggestion} may quote source code
 * — they are encrypted at rest in read models (DATA-MODEL.md §5, ADR-014).
 * {@code suggestion} is a proposed replacement, nullable; it is rendered as a
 * suggestion the human accepts, never auto-applied (SECURITY.md).
 */
public record Finding(String path, LineRange range, Severity severity, String message, String suggestion) {
}
