package dev.codespire.runtime.docker;

import java.util.List;

/** Placeholder: the detail of a sidecar that did not come up. */
final class SidecarStartFailure {

    static final int TAIL_LINES = 20;

    static final int TAIL_CHARS = 2048;

    static final String SEPARATOR = " | ";

    private SidecarStartFailure() {
    }

    record State(String status, Long exitCode, Boolean oomKilled) {
    }

    static String detail(String headline, State state, List<String> log) {
        return headline;
    }

    static List<String> tail(List<String> log) {
        return log;
    }
}
