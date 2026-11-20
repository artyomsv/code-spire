package dev.codespire.orchestrator.llm.catalog;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The operator's manual models ({@code llm_model} and its rates). A read fault is thrown, never
 * answered as "no such model": the caller must not fall through to the catalog on a database hiccup.
 */
public interface ManualModels {

    /**
     * The manual model answering to the earliest name in {@code names} that has one. The resolver
     * passes the asked name first, then the catalog entry's model id and aliases in file order.
     */
    Optional<ResolvedModel> byAnyName(List<String> names) throws SQLException;

    /** Every manual model. */
    List<ResolvedModel> all() throws SQLException;

    /** Every manual model's name. */
    Set<String> allNames() throws SQLException;
}
