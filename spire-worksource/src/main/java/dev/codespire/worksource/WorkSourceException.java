package dev.codespire.worksource;

/** Safe capability/read failure. Adapters must not put credentials or tracker bodies in the message. */
public final class WorkSourceException extends RuntimeException {
    public WorkSourceException(String safeReason) { super(safeReason); }
}
