package dev.codespire.orchestrator.llm.catalog;

/**
 * A validation message made safe to log and store. The message names a model id, an alias or a vendor
 * string from the fetched file, which may be anything: control characters (newlines in a log line) become
 * spaces, and the text is capped, so a hostile file cannot flood a log, the state row or the dashboard.
 */
final class CatalogText {

    static final int MAX_DETAIL = 200;
    private static final String ELLIPSIS = "…";

    private CatalogText() {
    }

    static String safe(String detail) {
        if (detail == null) {
            return "";
        }
        String flat = detail.replaceAll("\\p{Cntrl}", " ");
        return flat.length() <= MAX_DETAIL ? flat : flat.substring(0, MAX_DETAIL - ELLIPSIS.length()) + ELLIPSIS;
    }
}
