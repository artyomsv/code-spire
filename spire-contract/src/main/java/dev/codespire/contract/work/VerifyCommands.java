package dev.codespire.contract.work;

import java.util.List;

/**
 * The one rule for the operator's check commands (M4 verify): 0 to 20 non-blank single lines of at most 1000
 * characters. The build setup, the preparation that binds them and the command that carries them to the run
 * worker all apply it, so a setup that saves is one a verify can run.
 */
public final class VerifyCommands {

    public static final int MAX_COMMANDS = 20, MAX_COMMAND_CHARS = 1000;

    private VerifyCommands() {
    }

    /** Whether every command is acceptable, the list included. */
    public static boolean valid(List<String> commands) {
        return commands.size() <= MAX_COMMANDS && commands.stream().allMatch(VerifyCommands::validCommand);
    }

    /** @throws IllegalArgumentException naming the rule, never quoting a command */
    public static void requireValid(List<String> commands) {
        if (!valid(commands)) throw new IllegalArgumentException("Verify commands are 0-" + MAX_COMMANDS
                + " single lines of at most " + MAX_COMMAND_CHARS + " characters");
    }

    private static boolean validCommand(String command) {
        return command != null && !command.isBlank() && command.length() <= MAX_COMMAND_CHARS
                && command.indexOf('\n') < 0 && command.indexOf('\r') < 0;
    }
}
