package dev.codespire.gateway;

import dev.codespire.contract.command.CommentCommands;

import java.util.Set;

/**
 * The commands the ingresses translate. One set rather than three copies: this was
 * {@code Set.of("review")} written out separately in each webhook resource, so adding a command
 * meant remembering all three, and a provider left behind would silently route the new command to
 * the conversation path instead.
 */
public final class WebhookCommands {

    public static final Set<String> SUPPORTED =
            Set.of(CommentCommands.REVIEW, CommentCommands.FINDING, CommentCommands.FIX);

    private WebhookCommands() {
    }
}
