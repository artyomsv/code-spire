package dev.codespire.llmcatalog;

/**
 * A catalog file that could not be read. The message names the rule that failed, never the file's
 * text, and no cause is kept: a parser's own message quotes the input.
 */
public final class CatalogException extends Exception {

    public CatalogException(String rule) {
        super(rule);
    }
}
