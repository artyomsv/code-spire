package dev.codespire.contract.scm;

import java.util.List;

public record FilePatch(String oldPath,
                        String newPath,
                        ChangeType change,
                        String language,
                        boolean binary,
                        boolean tooLarge,
                        List<Hunk> hunks) {
}
