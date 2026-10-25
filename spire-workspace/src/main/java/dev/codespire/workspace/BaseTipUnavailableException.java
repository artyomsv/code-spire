package dev.codespire.workspace;

/**
 * A land fix named a merged base tip the publisher cannot use: the object is missing, or it is not an ancestor
 * of the tip being published (M4 slice 7a).
 *
 * <p>Typed, and not an {@code IOException}, so the publisher reports it as {@code BASE_TIP_UNAVAILABLE} and stops
 * rather than skipping it as an unreadable bundle. Without the ancestry rule a tip could name any commit and
 * widen the base-merge exclusion to paths the base never brought.
 */
public class BaseTipUnavailableException extends RuntimeException {

    public BaseTipUnavailableException(String message) {
        super(message);
    }
}
