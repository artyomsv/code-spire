package dev.codespire.worker.adapters;

import dev.codespire.context.code.JavaLanguageSupport;
import dev.codespire.context.code.TypeScriptLanguageSupport;
import dev.codespire.contract.port.LanguageSupport;
import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.FilePatch;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composition root for code-reference extraction — the code-side counterpart to
 * {@link WorkerContextReferences}.
 *
 * <p>Naming the supported languages is this class's whole job, same as its sibling: the pipeline
 * sees one {@link CodeReferences} value and never learns which {@link LanguageSupport} produced it.
 * A file whose language has no registered support contributes nothing, and its review proceeds
 * exactly as before.
 */
@ApplicationScoped
public class WorkerCodeReferences {

    private final Map<String, LanguageSupport> byLanguage = new HashMap<>();

    public WorkerCodeReferences() {
        register(new JavaLanguageSupport());
        register(new TypeScriptLanguageSupport());
    }

    private void register(LanguageSupport support) {
        for (String language : support.languages()) {
            byLanguage.put(language, support);
        }
    }

    /** What the diff's changed lines reference: the paths touched and the identifiers they mention. */
    public CodeReferences inDiff(Diff diff) {
        Set<String> changedPaths = new LinkedHashSet<>();
        Set<String> identifiers = new LinkedHashSet<>();
        for (FilePatch patch : diff.files()) {
            if (patch.binary() || patch.tooLarge()) {
                continue;
            }
            LanguageSupport support = byLanguage.get(patch.language());
            if (support == null) {
                continue;
            }
            identifiers.addAll(support.identifiersIn(patch));
            String path = patch.newPath() != null ? patch.newPath() : patch.oldPath();
            changedPaths.add(path);
        }
        if (changedPaths.isEmpty() || identifiers.isEmpty()) {
            return CodeReferences.empty();
        }
        return new CodeReferences(changedPaths, identifiers);
    }
}
