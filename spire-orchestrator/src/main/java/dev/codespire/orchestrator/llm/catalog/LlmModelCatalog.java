package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.llmcatalog.CatalogDocument;
import dev.codespire.llmcatalog.CatalogException;
import dev.codespire.llmcatalog.CatalogModel;
import dev.codespire.llmcatalog.CatalogParser;
import dev.codespire.llmcatalog.CatalogValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Answers every model question by name: manual models first, then the public catalog (spec §7.1).
 *
 * <p>A manual model shadows the whole catalog entry it names, by model id or by any alias, even when it
 * is switched off or has incomplete rates. A fault reading the manual tables is thrown as
 * {@link CatalogueReadFailed}, never answered from the catalog: that would price a shadowed model at
 * catalog rates or start a run on a model the operator switched off.
 *
 * <p>The catalog is loaded on its first use, synchronously, so no question is ever answered from an
 * empty catalog: the stored document, else the copy bundled in the image. With the URL {@code none}
 * the bundled copy is used alone and the stored row is ignored (spec §6.5).
 */
@ApplicationScoped
public class LlmModelCatalog {

    static final String URL_NONE = "none";
    private static final Logger LOG = Logger.getLogger(LlmModelCatalog.class);
    private static final Comparator<ResolvedModel> BY_TYPE_THEN_NAME =
            Comparator.comparing(ResolvedModel::type).thenComparing(ResolvedModel::name);

    @Inject
    ManualModels manual;

    @Inject
    LlmCatalogStateRepository states;

    @ConfigProperty(name = "spire.llm.catalog.url")
    String url;

    @ConfigProperty(name = "spire.llm.catalog.bundled-resource")
    String bundledResource;

    private Supplier<String> bundledText;
    private volatile CatalogSnapshot snapshot;

    public LlmModelCatalog() {
    }

    /** For tests: every collaborator by hand. */
    LlmModelCatalog(ManualModels manual, LlmCatalogStateRepository states, Supplier<String> bundledText, String url) {
        this.manual = manual;
        this.states = states;
        this.bundledText = bundledText;
        this.url = url;
    }

    /** A catalog entry as the display list shows it: shadowed when a manual model has any of its names. */
    public record CatalogRow(CatalogModel model, boolean shadowed) {
    }

    /** The manual tables could not be read, so the answer is unknown — never the catalog's. */
    public static final class CatalogueReadFailed extends RuntimeException {
        CatalogueReadFailed(Exception cause) {
            super("manual_models_unreadable", cause);
        }
    }

    public Optional<ResolvedModel> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Optional<CatalogModel> entry = snapshot().byName(name);
        List<String> asked = entry.map(found -> askedFirst(name, found)).orElse(List.of(name));
        Optional<ResolvedModel> manualModel;
        try {
            manualModel = manual.byAnyName(asked);
        } catch (SQLException e) {
            throw new CatalogueReadFailed(e);
        }
        if (manualModel.isPresent()) {
            return manualModel;
        }
        return entry.map(CatalogEntries::resolved);
    }

    /** One answer per name, the winner only, by type then name: what pickers and guards list. */
    public List<ResolvedModel> effective() {
        List<ResolvedModel> manualModels;
        try {
            manualModels = manual.all();
        } catch (SQLException e) {
            throw new CatalogueReadFailed(e);
        }
        Set<String> manualNames = manualModels.stream().map(ResolvedModel::name).collect(Collectors.toSet());
        List<ResolvedModel> models = new ArrayList<>(manualModels);
        for (CatalogModel entry : snapshot().models()) {
            if (Collections.disjoint(entry.names(), manualNames)) {
                models.add(CatalogEntries.resolved(entry));
            }
        }
        models.sort(BY_TYPE_THEN_NAME);
        return List.copyOf(models);
    }

    /** A catalog entry as the answer the resolver gives when nothing shadows it. */
    public static ResolvedModel asResolved(CatalogModel entry) {
        return CatalogEntries.resolved(entry);
    }

    /** Every catalog entry, each marked when a manual model shadows it. */
    public List<CatalogRow> catalogRows() {
        Set<String> manualNames;
        try {
            manualNames = manual.allNames();
        } catch (SQLException e) {
            throw new CatalogueReadFailed(e);
        }
        return snapshot().models().stream()
                .map(entry -> new CatalogRow(entry, !Collections.disjoint(entry.names(), manualNames)))
                .toList();
    }

    public CatalogSnapshot snapshot() {
        CatalogSnapshot current = snapshot;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (snapshot == null) {
                snapshot = load();
            }
            return snapshot;
        }
    }

    /** Swaps the catalog in use, whole. */
    public void replace(CatalogSnapshot next) {
        synchronized (this) {
            snapshot = next;
        }
    }

    /**
     * Swaps to {@code next} only while the catalog in use is still the one with {@code expectedSha}. A refresh
     * reads the catalog, stores its file, then swaps; a newer file another instance stored in between, and the
     * listener already installed, must not be overwritten by the older one. Answers whether it swapped.
     */
    public boolean replaceIf(String expectedSha, CatalogSnapshot next) {
        synchronized (this) {
            CatalogSnapshot current = snapshot;
            if (current != null && !java.util.Objects.equals(current.sha256(), expectedSha)) {
                return false;
            }
            snapshot = next;
            return true;
        }
    }

    /** Whether remote loading is off, so only the bundled copy is ever used and nothing is written. */
    public boolean remoteLoadingOff() {
        return url == null || URL_NONE.equalsIgnoreCase(url.strip());
    }

    /** The bundled copy as a snapshot; empty when the image carries none or it does not read. */
    public CatalogSnapshot bundled() {
        String text = bundledText();
        if (text == null) {
            LOG.error("No bundled LLM catalog in this image; catalog models are unpriced until a fetch succeeds");
            return CatalogSnapshot.EMPTY;
        }
        try {
            return parse(text);
        } catch (CatalogException e) {
            LOG.errorf("The bundled LLM catalog does not read (%s); catalog models are unpriced", CatalogText.safe(e.getMessage()));
            return CatalogSnapshot.EMPTY;
        }
    }

    /** The text of the copy built into the image, or null when it carries none. */
    public String bundledText() {
        return bundledText == null ? BundledCatalog.read(bundledResource) : bundledText.get();
    }

    /** The configured URL, as written. */
    public String url() {
        return url;
    }

    /** A document as a snapshot, after the lenient parse and the same rules CI applies. */
    public static CatalogSnapshot parse(String text) throws CatalogException {
        CatalogDocument doc = CatalogParser.parse(text, CatalogParser.Mode.LENIENT);
        // A day's grace: a file read today in a zone ahead of this host's UTC date is not "in the future".
        List<String> problems = CatalogValidator.problems(doc, LocalDate.now(ZoneOffset.UTC).plusDays(1));
        if (!problems.isEmpty()) {
            throw new CatalogException(problems.getFirst());
        }
        return CatalogSnapshot.of(doc, CatalogSnapshot.sha256Of(text));
    }

    private CatalogSnapshot load() {
        if (remoteLoadingOff()) {
            return bundled();
        }
        String stored;
        try {
            stored = states.read().document();
        } catch (IllegalStateException unreadable) {
            // Nothing is cached: the next question tries again. The manual tables sit in the same
            // database, so this question could not have been answered either way.
            throw new CatalogueReadFailed(unreadable);
        }
        if (stored == null) {
            return bundled();
        }
        try {
            return parse(stored);
        } catch (CatalogException e) {
            LOG.errorf("The stored LLM catalog does not read (%s); using the bundled copy", CatalogText.safe(e.getMessage()));
            return bundled();
        }
    }

    /** The asked name first, then the entry's names in file order: the manual row to prefer comes first. */
    private static List<String> askedFirst(String name, CatalogModel entry) {
        List<String> names = new ArrayList<>();
        names.add(name);
        entry.names().stream().filter(other -> !other.equals(name)).forEach(names::add);
        return names;
    }
}
