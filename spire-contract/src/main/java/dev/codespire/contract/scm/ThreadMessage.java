package dev.codespire.contract.scm;

/** One message in a review-thread transcript. {@code fromBot} lets the prompt label the bot's own turns. */
public record ThreadMessage(String author, String text, boolean fromBot) {
}
