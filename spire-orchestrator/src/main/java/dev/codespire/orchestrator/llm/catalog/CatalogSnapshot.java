package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.llmcatalog.CatalogDocument;
import dev.codespire.llmcatalog.CatalogModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One parsed catalog, never changed after it is built. A new file is a new snapshot swapped in whole,
 * so one pricing call reads one catalog from start to end.
 */
public final class CatalogSnapshot {

    static final CatalogSnapshot EMPTY = new CatalogSnapshot(List.of(), null);

    private final List<CatalogModel> models;
    private final Map<String, CatalogModel> byName;
    private final String sha256;

    private CatalogSnapshot(List<CatalogModel> models, String sha256) {
        this.models = List.copyOf(models);
        this.sha256 = sha256;
        Map<String, CatalogModel> names = new HashMap<>();
        for (CatalogModel model : this.models) {
            model.names().forEach(name -> names.putIfAbsent(name, model));
        }
        this.byName = Map.copyOf(names);
    }

    public static CatalogSnapshot of(CatalogDocument doc, String sha256) {
        return new CatalogSnapshot(doc.models(), sha256);
    }

    /** The entry answering to this name, as its model id or as an alias. */
    public Optional<CatalogModel> byName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(name));
    }

    public List<CatalogModel> models() {
        return models;
    }

    public String sha256() {
        return sha256;
    }

    /** The hex SHA-256 of a document's UTF-8 bytes: how two copies of the catalog are told apart. */
    public static String sha256Of(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
