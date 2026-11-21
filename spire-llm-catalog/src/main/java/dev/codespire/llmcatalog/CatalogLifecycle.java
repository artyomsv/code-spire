package dev.codespire.llmcatalog;

import java.time.LocalDate;

/** Where a model is in its vendor's lifecycle. A model leaves the catalog only by becoming RETIRED. */
public record CatalogLifecycle(Status status, LocalDate deprecationDate, LocalDate retirementDate, String successor) {

    public enum Status { ACTIVE, DEPRECATED, RETIRED }
}
