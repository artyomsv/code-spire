package dev.codespire.orchestrator.provider;

/**
 * Which registrations serve one (forge type, workspace), per role — the answer the Repositories
 * screen shows beside every row (spec 2026-09-07-accounts-and-roles §6.1).
 *
 * <p>Computed from the same resolvers the pipeline uses — {@code ProviderRegistry.resolve} for the
 * reviewer, {@code MachineAccounts.resolve} for the factory — so the screen cannot say one thing
 * while a webhook or a {@code /fix} does another. Never carries a secret: it is built from
 * {@link ProviderView}, which has none.
 */
public record ServingAccounts(String type, String workspace, ServingAccount reviewer, ServingAccount factory) {

    /**
     * One role's answer. {@code state} is a closed set the dashboard mirrors:
     * {@code ok | no-identity | no-login | disabled | missing}. Every other field is null for
     * {@code missing} and set for the rest.
     */
    public record ServingAccount(String state, String id, String name, String botUsername, String botAccountId) {

        static ServingAccount missing() {
            return new ServingAccount("missing", null, null, null, null);
        }

        static ServingAccount of(String state, ProviderView view) {
            return new ServingAccount(state, view.id(), view.name(), view.botUsername(), view.botAccountId());
        }
    }
}
