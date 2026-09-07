# Accounts and Roles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate *who acts* (accounts) from *where it acts* (repositories, context sources) in Settings, and let an operator register the FACTORY-role account the M2 factory needs from the UI.

**Architecture:** Three Settings screens are renamed and reshaped (Providers → Accounts with a People tab; Operators → that People tab; Webhooks → Repositories) on top of the existing `scm_provider` / `context_provider` / `webhook_repo` tables — no migration. The account form gains a Role field that is fixed after registration (a changing `PUT` gets 409). One new read-only endpoint, `GET /api/providers/serving`, answers "which accounts serve this forge + workspace" with the same resolvers the pipeline uses, and the Repositories screen shows that answer per row.

**Tech Stack:** Java 25 / Quarkus 3.38.3 (JAX-RS, JDBC, RestAssured + `@TestSecurity` tests on Dev Services Postgres); React 19 + TypeScript + react-router 8 (HashRouter) + vitest 4 + Testing Library; Gradle Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-09-07-accounts-and-roles-design.md` — read it first; section numbers below (§5.1, §6.1 …) refer to it.

**Branch:** `feat/accounts-and-roles` in worktree `E:\Projects\Stukans\code-spire-worktrees\feat-software-factory`. Draft PR #120 tracks progress; its checklist is ticked by the lead after each task.

## Global Constraints

Every task's requirements implicitly include this section.

- **Commit messages:** imperative mood, first line ≤ 72 chars, body for non-trivial changes. **Never mention agentic/AI authoring** — no `Co-Authored-By: Claude…`, no model or vendor names, no "generated with". Describe what changed and why. Commit after every task; do not push (the lead pushes).
- **Line endings:** Java sources in this repo are CRLF; TypeScript is whatever the file already uses. Never convert a file's line endings. Edit scripts must match on `\r?\n`.
- **Formatting:** 4-space indent in Java, 2-space in TS/TSX/CSS/MD. Explicit types over `var` in Java. `interface` over `type` for object shapes in TypeScript (type aliases only for unions).
- **Icons:** lucide-react only. Never emoji.
- **React caps (`~/.claude/rules/clean-code-react.md`):** max 250 lines per file, max 8 `useState` per component. `SettingsProviders.tsx` (585) and `SettingsWebhookRepos.tsx` (586) already exceed the size cap — Task 7 splits the first; Tasks 10 keeps new code in new files. Where a pre-existing overage remains, record it as a `techdebt/spire-ui/<criticality>-<complexity>-<slug>.md` entry (Task 9 does this for the form's `useState` count).
- **No synthetic data in user-visible tables.** Tests use `TEST-` prefixed names, as the existing tests do. Never insert plausible rows into the dev database.
- **Gradle:** always pass `--rerun` (a cached `test` task silently runs nothing). **Never run two Gradle invocations at once in this worktree** — it corrupts `build/test-results`. One test class at a time: `./gradlew :spire-orchestrator:test --tests 'fully.qualified.ClassName' --rerun`. The orchestrator and gateway tests boot Quarkus with Dev Services (Postgres + Kafka via Docker) — allow ~90 s for the first class in a session. On PowerShell use `.\gradlew.bat`; on Git Bash `./gradlew`.
- **UI tests:** from `spire-ui/`: `npx vitest run <path>` for one file, `npm test` for all, `npx tsc --noEmit` for types. `styles.contract.test.ts` fails the build if a literal `className="…"` names a class `src/index.css` does not define — add CSS in the same task that adds the class.
- **Comments:** the codebase explains *why* in javadoc/JSDoc, often at length, citing the incident that motivated the code. Match that register; do not add comments that restate the code.
- **Scratch files** go in the session scratchpad, never `/tmp`, never the repo.
- **Provider neutrality (ADR-020):** core modules never name a forge. The serving endpoint switches on nothing forge-specific.
- **The API never returns a secret.** `ProviderView` has `hasSecret` only; `ServingAccounts` is built from `ProviderView`.

---

## File Map

**Backend — `spire-orchestrator/src/main/java/dev/codespire/orchestrator/`**

| File | Change | Responsibility |
|---|---|---|
| `provider/ProviderRegistry.java` | modify | + `registration(type, workspace, role)` (a view, enabled or not); `update` refuses a role change with `RoleIsFixedAtRegistration` |
| `provider/ProviderResource.java` | modify | + `GET /serving`; `update` maps `RoleIsFixedAtRegistration` → 409; javadoc string |
| `provider/ServingAccounts.java` | create | the `/serving` response record and its per-role `ServingAccount` |
| `factory/RunResource.java:188`, `ingress/ManualRegisterResource.java:91`, `pipeline/ReviewRerunService.java:69`, `prompt/PromptSampleRenderer.java:72`, `attention/AttentionQueries.java:159,176,348` | modify | screen names and links (§6.3) |

**Backend — `spire-gateway/src/main/java/dev/codespire/gateway/`**

| File | Change |
|---|---|
| `attention/WebhookAttentionRows.java:56` | `/settings/webhooks?edit=` → `/settings/repositories?edit=` |
| `registry/WebhookRepoResource.java:25` | javadoc string |

**Tests (Java)** — `spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderRegistryTest.java` (+1 test), `…/provider/ProviderResourceTest.java` (third part of the FACTORY test → 409), `…/provider/ProviderServingResourceTest.java` (create), `…/attention/AttentionQueriesTest.java:411`, `spire-gateway/src/test/java/dev/codespire/gateway/attention/WebhookAttentionResourceTest.java:108`.

**UI — `spire-ui/src/`**

| File | Change | Responsibility |
|---|---|---|
| `api.ts` | modify | `ProviderRole`, `ProviderView.role`, `ProviderInput.role?`, `ServingState`, `ServingAccount`, `ServingAccounts`, `fetchServingAccounts` |
| `components/accounts.ts` | create | pure: `roleLabel`, `accountKind`, `hostOf` |
| `components/servingAccounts.ts` | create | pure: `servingChip(account, failure)` — state → label/pill/title, unknown → grey |
| `components/RedirectKeepingQuery.tsx` | create | `<Navigate>` that carries `location.search` |
| `components/AccountsTabs.tsx` | create | the two-anchor tab strip (plain `href="#/…"`, no router hooks) |
| `components/AccountsTable.tsx` | create (Task 7 extracts, Task 8 extends) | the machine-accounts table incl. `ConnCell`, tracker rows |
| `components/ProviderFormModal.tsx` | create (Task 7 extracts, Task 9 extends) | `ProviderFormModal` + `DeleteConfirmModal` |
| `components/SettingsProviders.tsx` | modify | becomes the Accounts page: load, tabs, card, modals |
| `components/SettingsOperators.tsx` | modify | renders `AccountsTabs` (People) |
| `components/SettingsWebhookRepos.tsx` | modify | Repositories: title, notes, picker label + reviewer filter, two serving columns |
| `components/ServingCell.tsx` | create | one chip + Verify for one role on one repo row |
| `hooks/useServingAccounts.ts` | create | one `/serving` request per distinct (forge, owner); `ownerOf`, `servingKey` |
| `components/SettingsContextProviders.tsx` | modify | "Used by" column |
| `components/AttentionBell.tsx` | modify | `ACTION_LABELS` |
| `App.tsx` | modify | `TITLES`, flags, rail, routes, redirects |
| `index.css` | modify | `.tabs`, `.tab`, `.serving-cell`, `.serving-verify` |

**UI tests** — `App.routes.test.tsx`, `components/AttentionBell.test.tsx`, `components/SettingsProviders.form.test.tsx`, `components/SettingsProviders.test.ts`, `components/SettingsProviders.accounts.test.tsx` (create), `components/accounts.test.ts` (create), `components/servingAccounts.test.ts` (create), `components/AccountsTabs.test.tsx` (create), `components/SettingsOperators.test.tsx` (+1), `components/SettingsWebhookRepos.form.test.tsx`, `components/SettingsWebhookRepos.serving.test.tsx` (create), `hooks/useServingAccounts.test.tsx` (create), `components/SettingsContextProviders.form.test.tsx` (+1).

**Docs** — `README.md:80-81`, `docs/ROADMAP.md:53`, `docs/SMOKE-TEST.md` (lines 63, 87, 198, 265, 608, 808, 1589, 1615, 1644-1652), `docs/HISTORY.md` (one bullet), `docker-compose.dev.yml:233`, `techdebt/spire-ui/4-3-the-account-form-holds-thirteen-state-hooks.md` (create).

---

### Task 1: A role is fixed after registration (backend)

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderRegistry.java` (the `update` method, lines ~101-146)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderResource.java` (`update`, lines ~90-104; imports)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderRegistryTest.java`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderResourceTest.java` (lines 112-120)

**Interfaces:**
- Produces: `public static final class ProviderRegistry.RoleIsFixedAtRegistration extends RuntimeException` with constructor `(ProviderRole stored, ProviderRole requested)`; `ProviderRegistry.update(UUID, ProviderInput)` throws it when `in.role()` is non-blank and differs from the stored role. `ProviderResource.update` answers `409` with the exception's message as the body.

- [ ] **Step 1: Write the failing registry test**

Append to `ProviderRegistryTest` (inside the class, before `rawSecret`). Add `import static org.junit.jupiter.api.Assertions.assertThrows;` to the imports.

```java
    private static ProviderInput withRole(String workspace, String role) {
        return new ProviderInput("Role bot", "github", "https://api.github.com", workspace, "bearer", null,
                "TEST-token", "TEST-acct", true, List.of(), "role-bot", null, role);
    }

    /**
     * A registration's role is fixed for its lifetime (spec 2026-09-07 §4, decision 2).
     *
     * <p>Changing it re-purposes one token under the other authority set — a reviewer that suddenly
     * holds the push identity, or a factory account the review path starts posting as. HISTORY records
     * the day a role-less PUT did that by accident (V44's COALESCE is the repair); an explicit change
     * is the same event on purpose. The cure is the one an operator applies to any service account:
     * register the new one, delete the old one.
     */
    @Test
    void aRoleIsFixedAtRegistration() {
        UUID id = UUID.fromString(registry.create(withRole("ws-role-fixed", "FACTORY")).id());

        assertThrows(ProviderRegistry.RoleIsFixedAtRegistration.class,
                () -> registry.update(id, withRole("ws-role-fixed", "REVIEWER")));
        assertEquals("FACTORY", registry.get(id).orElseThrow().role(), "a refused change writes nothing");

        // The same role, and no role at all, both mean "keep it" — the dashboard's edit form sends
        // the stored role; older clients send none.
        assertTrue(registry.update(id, withRole("ws-role-fixed", "FACTORY")).isPresent());
        assertTrue(registry.update(id, withRole("ws-role-fixed", null)).isPresent());
        assertEquals("FACTORY", registry.get(id).orElseThrow().role());
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.provider.ProviderRegistryTest' --rerun`
Expected: compilation error — `RoleIsFixedAtRegistration` does not exist.

- [ ] **Step 3: Implement the guard in `ProviderRegistry`**

Add the nested exception at the bottom of the class (before `aad`):

```java
    /**
     * A registration's role is fixed for its lifetime (spec 2026-09-07-accounts-and-roles, decision 2).
     *
     * <p>Changing it would re-purpose one token under the other authority set: a reviewer that
     * suddenly holds the push identity, or a factory account that the review path starts posting
     * as. HISTORY records the day a role-less PUT did exactly that by accident; an explicit change
     * is the same event on purpose. The cure is the one an operator would apply to any real service
     * account — register the new one, delete the old one — and the message says so.
     */
    public static final class RoleIsFixedAtRegistration extends RuntimeException {
        public RoleIsFixedAtRegistration(ProviderRole stored, ProviderRole requested) {
            super("The role is set at registration and this account is " + stored + "; it cannot become "
                    + requested + ". Register a new account for that role and delete this one.");
        }
    }
```

In `update`, replace

```java
            if (!exists(c, id)) {
                return Optional.empty();
            }
```

with

```java
            Optional<ProviderRole> stored = storedRole(c, id);
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            if (in.role() != null && !in.role().isBlank() && ProviderRole.of(in.role()) != stored.get()) {
                throw new RoleIsFixedAtRegistration(stored.get(), ProviderRole.of(in.role()));
            }
```

Update the comment above `ps.setString(10, …)` so it no longer says an explicit role changes it:

```java
                // An absent role keeps the stored one (COALESCE above); a present one was checked
                // equal to it just above, so this write can only ever repeat the stored value. The
                // dashboard's edit form sends the stored role; older clients send none.
```

Replace the private `exists` helper with:

```java
    /** The stored role, or empty when no such provider — one read serves both the 404 and the guard. */
    private Optional<ProviderRole> storedRole(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT role FROM scm_provider WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ProviderRole.valueOf(rs.getString("role"))) : Optional.empty();
            }
        }
    }
```

(`exists` has no other caller — confirm with `grep -n "exists(" ProviderRegistry.java` before deleting it.)

- [ ] **Step 4: Run the registry test to verify it passes**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.provider.ProviderRegistryTest' --rerun`
Expected: PASS, all tests in the class.

- [ ] **Step 5: Change the REST test that asserts the old behaviour**

In `ProviderResourceTest.aFactoryRoleSurvivesTheRestPathOnCreateAndUpdate`, replace the third block (from the comment `// An explicit role on update still changes it` to the end of the method) with:

```java
        // A role is fixed at registration: changing it would re-purpose this token under the other
        // authority set. The request is refused, and both lookups still answer as before.
        update.put("role", "REVIEWER");
        given().contentType("application/json").body(update)
                .when().put("/api/providers/" + id)
                .then().statusCode(409);
        org.junit.jupiter.api.Assertions.assertTrue(
                registry.resolve("bitbucket-cloud", "rest-factory", ProviderRole.FACTORY).isPresent());
        org.junit.jupiter.api.Assertions.assertTrue(
                registry.resolve("bitbucket-cloud", "rest-factory").isEmpty(),
                "a refused change must not have written the other role");
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.provider.ProviderResourceTest' --rerun`
Expected: FAIL — `aFactoryRoleSurvivesTheRestPathOnCreateAndUpdate` gets 500 (the registry's exception is unmapped), not 409.

- [ ] **Step 7: Map the exception in `ProviderResource.update`**

Add `import jakarta.ws.rs.ClientErrorException;` and replace the body of `update` with:

```java
    @PUT
    @RolesAllowed("spire-admin")
    @Path("/{id}")
    public ProviderView update(@PathParam("id") String id, ProviderInput in) {
        validate(in, false);
        ProviderView updated;
        try {
            updated = registry.update(uuid(id), resolveIdentity(in))
                    .orElseThrow(() -> new NotFoundException("No provider " + id));
        } catch (ProviderRegistry.RoleIsFixedAtRegistration e) {
            throw conflict(e.getMessage());
        }
        // Only record when a secret was actually supplied: that's the only case resolveIdentity(...)
        // re-validates the token (it returns the input untouched on a blank secret). Recording success
        // unconditionally would silently clear a real prior rejection on an update that never touched
        // the credential at all.
        if (in.secret() != null && !in.secret().isBlank()) {
            registry.recordCheck(uuid(id), true, null);
        }
        return updated;
    }
```

Add the helper beside `requireField` at the bottom:

```java
    /**
     * The message goes on the RESPONSE, not the exception: {@code new ClientErrorException(message,
     * status)} leaves the body empty, so the client learns nothing about what to send instead.
     * Same shape as {@code RunResource.conflict}.
     */
    private static ClientErrorException conflict(String message) {
        return new ClientErrorException(
                Response.status(Response.Status.CONFLICT).entity(message).build());
    }
```

- [ ] **Step 8: Run both test classes**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.provider.ProviderResourceTest' --tests 'dev.codespire.orchestrator.provider.ProviderRegistryTest' --rerun`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderRegistry.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderResource.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderRegistryTest.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderResourceTest.java
git commit -m "Fix an account's role at registration

A PUT that names a different role is refused with 409. Changing a role
re-purposes one token under the other authority set: the review path
would post as the push identity, or the factory would push as the bot.
A role-less PUT keeps the stored role, as before; an equal one is a
no-op. Registering a new account and deleting the old one is the way
to change a role, and the refusal says so."
```

---

### Task 2: `GET /api/providers/serving` (backend)

**Files:**
- Create: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ServingAccounts.java`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderRegistry.java` (+ `registration`)
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderResource.java` (+ `serving`, + `@Inject MachineAccounts`)
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderServingResourceTest.java` (create)

**Interfaces:**
- Consumes: `MachineAccounts.resolve(ScmType, String)` (`…/factory/MachineAccounts.java`), `ScmType.fromProviderType(String)` (`spire-contract/…/port/ScmType.java`), `ProviderRegistry.resolve(type, workspace, role)`.
- Produces: `GET /api/providers/serving?type=&workspace=` → `ServingAccounts(String type, String workspace, ServingAccount reviewer, ServingAccount factory)`; `ServingAccounts.ServingAccount(String state, String id, String name, String botUsername, String botAccountId)`, `state ∈ ok | no-identity | no-login | disabled | missing`. `ProviderRegistry.registration(String type, String workspace, ProviderRole role): Optional<ProviderView>`.

- [ ] **Step 1: Write the failing REST test**

Create `ProviderServingResourceTest.java`:

```java
package dev.codespire.orchestrator.provider;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code GET /api/providers/serving}: which registrations serve a (forge type, workspace), per role,
 * in one of five states — the answer the Repositories screen shows beside each row.
 *
 * <p>Rows are written through the registry rather than the REST path, because the REST path
 * resolves a login from the token owner and this test needs rows WITHOUT one: the two amber states
 * exist to name exactly that condition. Every workspace is unique per test so the shared Dev
 * Services database never makes two tests see each other's rows.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ProviderServingResourceTest {

    @Inject
    ProviderRegistry registry;

    private static String workspace(String label) {
        return "TEST-serving-" + label + "-" + UUID.randomUUID();
    }

    private static ProviderInput row(String workspace, String name, String role, String botAccountId,
                                     String botUsername, boolean enabled) {
        return new ProviderInput(name, "github", "https://api.github.com", workspace, "bearer", null,
                "TEST-token-" + name, botAccountId, enabled, List.of(), botUsername, null, role);
    }

    private static io.restassured.response.ValidatableResponse serving(String workspace) {
        return given().queryParam("type", "github").queryParam("workspace", workspace)
                .when().get("/api/providers/serving")
                .then().statusCode(200)
                .body("type", equalTo("github"))
                .body("workspace", equalTo(workspace));
    }

    @Test
    void nothingRegisteredIsMissingOnBothRoles() {
        String ws = workspace("none");
        serving(ws)
                .body("reviewer.state", equalTo("missing"))
                .body("reviewer.id", nullValue())
                .body("factory.state", equalTo("missing"))
                .body("factory.name", nullValue());
    }

    @Test
    void anEnabledReviewerWithAnIdentityIsOk() {
        String ws = workspace("rev-ok");
        registry.create(row(ws, "TEST-reviewer", null, "TEST-acct-1", "test-bot", true));
        serving(ws)
                .body("reviewer.state", equalTo("ok"))
                .body("reviewer.name", equalTo("TEST-reviewer"))
                .body("reviewer.botUsername", equalTo("test-bot"))
                .body("reviewer.botAccountId", equalTo("TEST-acct-1"))
                .body("factory.state", equalTo("missing"));
    }

    /** The condition under which ConversationSaga skips every follow-up: the bot cannot recognise itself. */
    @Test
    void aReviewerWithNoResolvedIdentityIsFlaggedNotGreen() {
        String ws = workspace("rev-noid");
        registry.create(row(ws, "TEST-reviewer", null, "", null, true));
        serving(ws).body("reviewer.state", equalTo("no-identity"));
    }

    /** Registered-but-disabled has a different cure from missing, so it is a different word. */
    @Test
    void aDisabledRowIsDisabledNotMissing() {
        String ws = workspace("disabled");
        registry.create(row(ws, "TEST-reviewer", null, "TEST-acct-1", "test-bot", false));
        registry.create(row(ws, "TEST-factory", "FACTORY", "TEST-acct-2", "test-factory", false));
        serving(ws)
                .body("reviewer.state", equalTo("disabled"))
                .body("reviewer.name", equalTo("TEST-reviewer"))
                .body("factory.state", equalTo("disabled"))
                .body("factory.name", equalTo("TEST-factory"));
    }

    /** The same filter MachineAccounts.resolve applies before a dispatch: no login, no push. */
    @Test
    void aFactoryWithNoLoginCannotPush() {
        String ws = workspace("fac-nologin");
        registry.create(row(ws, "TEST-factory", "FACTORY", "TEST-acct-2", null, true));
        serving(ws)
                .body("factory.state", equalTo("no-login"))
                .body("factory.name", equalTo("TEST-factory"))
                .body("reviewer.state", equalTo("missing"));
    }

    @Test
    void aFactoryWithALoginIsOk() {
        String ws = workspace("fac-ok");
        registry.create(row(ws, "TEST-factory", "FACTORY", "TEST-acct-2", "test-factory", true));
        serving(ws)
                .body("factory.state", equalTo("ok"))
                .body("factory.botUsername", equalTo("test-factory"));
    }

    @Test
    void theResponseCarriesNoSecretField() {
        String ws = workspace("nosecret");
        registry.create(row(ws, "TEST-reviewer", null, "TEST-acct-1", "test-bot", true));
        serving(ws)
                .body("reviewer.secret", nullValue())
                .body("reviewer.authSecret", nullValue())
                .body("reviewer.hasSecret", nullValue());
    }

    @Test
    void blankParametersAreA400() {
        given().queryParam("type", "").queryParam("workspace", "x")
                .when().get("/api/providers/serving").then().statusCode(400);
        given().queryParam("type", "github")
                .when().get("/api/providers/serving").then().statusCode(400);
    }

    /** A registry read is an inventory of the deployment's reach; viewers are refused, like every other. */
    @Test
    @TestSecurity(user = "test-viewer", roles = {"spire-viewer"})
    void aViewerIsRefused() {
        given().queryParam("type", "github").queryParam("workspace", "TEST-viewer")
                .when().get("/api/providers/serving").then().statusCode(403);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.provider.ProviderServingResourceTest' --rerun`
Expected: FAIL — every request answers 404 (the `/{id}` route treats `serving` as an id and finds no provider).

- [ ] **Step 3: Create `ServingAccounts.java`**

```java
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
```

- [ ] **Step 4: Add `registration` to `ProviderRegistry`**

Insert after `resolveById`:

```java
    /**
     * The registration for a (type, workspace, role), <b>enabled or not</b>, as a view — for saying
     * which account WOULD serve and in what state, never for acting as it. {@link #resolve} is the
     * only method that hands out a usable credential, and it filters {@code enabled}; this one exists
     * so a disabled row can be reported as "disabled" rather than confused with "missing", which
     * an operator fixes differently.
     */
    public Optional<ProviderView> registration(String type, String workspace, ProviderRole role) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM scm_provider WHERE type = ? AND workspace = ? AND role = ?")) {
            ps.setString(1, type);
            ps.setString(2, workspace);
            ps.setString(3, role.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID id = rs.getObject("id", UUID.class);
                return Optional.of(toView(rs, authorsOf(c, id)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the " + role + " registration for "
                    + type + "/" + workspace, e);
        }
    }
```

- [ ] **Step 5: Add the endpoint to `ProviderResource`**

Imports to add:

```java
import dev.codespire.contract.port.ScmType;
import dev.codespire.orchestrator.factory.MachineAccounts;
import dev.codespire.orchestrator.provider.ServingAccounts.ServingAccount;
import jakarta.ws.rs.QueryParam;
```

Field, after `ProviderClients clients;`:

```java
    /** The dispatch path's own filter, so the factory chip and the 409 cannot disagree. */
    @Inject
    MachineAccounts machineAccounts;
```

Method, placed before `check`:

```java
    /**
     * Which accounts serve a (forge type, workspace), per role — for the Repositories screen.
     *
     * <p>Reads the same rows through the same rules the pipeline applies. An enabled REVIEWER row is
     * {@code ok} when its bot identity resolved and {@code no-identity} otherwise — the condition
     * under which {@code ConversationSaga} skips every follow-up. An enabled FACTORY row is
     * {@code ok} exactly when {@link MachineAccounts#resolve} would hand it to a run, and
     * {@code no-login} otherwise — the case {@code POST /api/runs} answers 409 for. A registered
     * but disabled row is {@code disabled}, not {@code missing}: the two have different cures.
     * Nothing here names a forge; {@link ScmType#fromProviderType} does the one translation.
     */
    @GET
    @Path("/serving")
    public ServingAccounts serving(@QueryParam("type") String type, @QueryParam("workspace") String workspace) {
        requireField(type, "type");
        requireField(workspace, "workspace");
        ServingAccount reviewer = registry.registration(type, workspace, ProviderRole.REVIEWER)
                .map(v -> !v.enabled() ? ServingAccount.of("disabled", v)
                        : isBlank(v.botAccountId()) ? ServingAccount.of("no-identity", v)
                        : ServingAccount.of("ok", v))
                .orElseGet(ServingAccount::missing);
        ServingAccount factory = registry.registration(type, workspace, ProviderRole.FACTORY)
                .map(v -> !v.enabled() ? ServingAccount.of("disabled", v)
                        : canPush(type, workspace) ? ServingAccount.of("ok", v)
                        : ServingAccount.of("no-login", v))
                .orElseGet(ServingAccount::missing);
        return new ServingAccounts(type, workspace, reviewer, factory);
    }

    private boolean canPush(String type, String workspace) {
        return ScmType.fromProviderType(type)
                .flatMap(scm -> machineAccounts.resolve(scm, workspace))
                .isPresent();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
```

`requireField` already throws `BadRequestException(name + " is required")`, which is the 400 the test expects. JAX-RS matches the literal `/serving` before the `/{id}` template, so `get(@PathParam("id"))` is not reached.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.provider.ProviderServingResourceTest' --rerun`
Expected: PASS (9 tests).

- [ ] **Step 7: Run the neighbouring classes once**

Run: `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.provider.*' --tests 'dev.codespire.orchestrator.factory.MachineAccountsTest' --rerun`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ServingAccounts.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderRegistry.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderResource.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/provider/ProviderServingResourceTest.java
git commit -m "Answer which accounts serve a workspace, per role

GET /api/providers/serving?type=&workspace= reports the reviewer and
the factory registration for one forge and workspace in one of five
states: ok, no-identity, no-login, disabled, missing. The factory
answer comes from MachineAccounts.resolve, the filter the dispatch
path applies, so the screen that shows it and the 409 that refuses a
run cannot disagree. Admin-only, like every registry read; built from
the view, so it can carry no secret."
```

---

### Task 3: Every message and link names a screen that exists (backend + gateway)

**Files:**
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunResource.java:186-189`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/ManualRegisterResource.java:91`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ReviewRerunService.java:69`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptSampleRenderer.java:72`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java:159,176,348`
- Modify: `spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderResource.java:29`
- Modify: `spire-gateway/src/main/java/dev/codespire/gateway/attention/WebhookAttentionRows.java:56`
- Modify: `spire-gateway/src/main/java/dev/codespire/gateway/registry/WebhookRepoResource.java:25`
- Modify: `docker-compose.dev.yml:233`
- Test: `spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/AttentionQueriesTest.java:411`
- Test: `spire-gateway/src/test/java/dev/codespire/gateway/attention/WebhookAttentionResourceTest.java:108`

**Interfaces:**
- Produces: attention actions `/settings/accounts`, `/settings/accounts?edit=<id>`, `/settings/repositories?edit=<id>` (consumed by Task 5's `ACTION_LABELS` and redirects).

- [ ] **Step 1: Change the two tests first**

`AttentionQueriesTest.java:411`:

```java
        assertTrue(row.action().startsWith("/settings/accounts?edit="), row.action());
```

`WebhookAttentionResourceTest.java:108`:

```java
                        startsWith("/settings/repositories?edit="));
```

- [ ] **Step 2: Run both to verify they fail**

Run (one after the other, never together):
`./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.attention.AttentionQueriesTest' --rerun`
then `./gradlew :spire-gateway:test --tests 'dev.codespire.gateway.attention.WebhookAttentionResourceTest' --rerun`
Expected: each FAILS on the changed assertion only.

- [ ] **Step 3: Make the string changes**

| File | Old | New |
|---|---|---|
| `RunResource.java:188` | `"account under Settings -> Providers with role FACTORY (ADR-038). "` | `"account under Settings -> Accounts with role Factory (ADR-038). "` |
| `ManualRegisterResource.java:91` | `". Add one under Settings -> Providers."` | `". Add one under Settings -> Accounts."` |
| `ReviewRerunService.java:69` | `"'. Add one under Settings -> Providers."` | `"'. Add one under Settings -> Accounts."` |
| `PromptSampleRenderer.java:72` | `" — add one under Settings -> Providers."` | `" — add one under Settings -> Accounts."` |
| `AttentionQueries.java:159` | `"/settings/providers"));` | `"/settings/accounts"));` |
| `AttentionQueries.java:176` | `editLink("/settings/providers", …)` | `editLink("/settings/accounts", …)` |
| `AttentionQueries.java:348` | `"/settings/providers", "source-control provider"),` | `"/settings/accounts", "source-control provider"),` |
| `ProviderResource.java:29` | `(spire-ui Settings -> Providers).` | `(spire-ui Settings -> Accounts).` |
| `WebhookAttentionRows.java:56` | `return "/settings/webhooks?edit=" + id;` | `return "/settings/repositories?edit=" + id;` |
| `WebhookRepoResource.java:25` | `(spire-ui Settings -> Webhooks).` | `(spire-ui Settings -> Repositories).` |
| `docker-compose.dev.yml:233` | `register under Settings -> Webhooks to` | `register under Settings -> Repositories to` |

Then `grep -rnE "Settings -> (Providers|Webhooks)|settings/providers|settings/webhooks" --include=*.java --include=*.yml --include=*.kts .` must return nothing outside `docs/` and `spire-ui/`.

- [ ] **Step 4: Run both tests to verify they pass**

Same two commands as Step 2, one after the other. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-orchestrator/src/main/java/dev/codespire/orchestrator/factory/RunResource.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/ingress/ManualRegisterResource.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/pipeline/ReviewRerunService.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/prompt/PromptSampleRenderer.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/attention/AttentionQueries.java \
        spire-orchestrator/src/main/java/dev/codespire/orchestrator/provider/ProviderResource.java \
        spire-orchestrator/src/test/java/dev/codespire/orchestrator/attention/AttentionQueriesTest.java \
        spire-gateway/src/main/java/dev/codespire/gateway/attention/WebhookAttentionRows.java \
        spire-gateway/src/main/java/dev/codespire/gateway/registry/WebhookRepoResource.java \
        spire-gateway/src/test/java/dev/codespire/gateway/attention/WebhookAttentionResourceTest.java \
        docker-compose.dev.yml
git commit -m "Send operators to Settings screens that exist

Server messages and attention links named Settings -> Providers and
Settings -> Webhooks. The nav calls the first Repositories and the
dashboard is about to call it Accounts and the second Repositories.
Every string now points at the screen the operator will find."
```

---

### Task 4: UI types, API functions and the two pure helper modules

**Files:**
- Modify: `spire-ui/src/api.ts` (`ProviderView` lines 311-327, `ProviderInput` 330-342; add after `verifyRepo`, ~line 429)
- Create: `spire-ui/src/components/accounts.ts`
- Create: `spire-ui/src/components/servingAccounts.ts`
- Test: `spire-ui/src/components/accounts.test.ts` (create)
- Test: `spire-ui/src/components/servingAccounts.test.ts` (create)
- Modify: every test fixture that builds a `ProviderView` (found by `npx tsc --noEmit`; known: `SettingsProviders.form.test.tsx` `existing`, `SettingsWebhookRepos.form.test.tsx` `provider()`)

**Interfaces:**
- Produces (in `api.ts`): `type ProviderRole = 'REVIEWER' | 'FACTORY'`; `ProviderView.role: ProviderRole`; `ProviderInput.role?: ProviderRole`; `type ServingState = 'ok' | 'no-identity' | 'no-login' | 'disabled' | 'missing'`; `interface ServingAccount { state: ServingState; id: string | null; name: string | null; botUsername: string | null; botAccountId: string | null }`; `interface ServingAccounts { type: string; workspace: string; reviewer: ServingAccount; factory: ServingAccount }`; `fetchServingAccounts(type: string, workspace: string): Promise<ServingAccounts>`.
- Produces (in `accounts.ts`): `roleLabel(role: string | null | undefined): string` → `'Reviewer' | 'Factory' | 'Unknown (<raw>)'`; `accountKind(contextType: string): 'Knowledge' | 'Tracker'`; `hostOf(baseUrl: string): string`.
- Produces (in `servingAccounts.ts`): `interface ServingChip { label: string; pill: string; title: string }`; `servingChip(account: ServingAccount | undefined, failure: string | null): ServingChip`.

- [ ] **Step 1: Write the failing pure tests**

`spire-ui/src/components/accounts.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { accountKind, hostOf, roleLabel } from './accounts';

describe('roleLabel', () => {
  it('names the two roles the server knows', () => {
    expect(roleLabel('REVIEWER')).toBe('Reviewer');
    expect(roleLabel('FACTORY')).toBe('Factory');
  });

  /** A role arrives as JSON. A value the union does not list must not fall into either real label. */
  it('marks anything else as unknown, never as a real role', () => {
    expect(roleLabel('OVERLORD')).toBe('Unknown (OVERLORD)');
    expect(roleLabel(null)).toBe('Unknown ()');
    expect(roleLabel(undefined)).toBe('Unknown ()');
  });
});

describe('accountKind', () => {
  it('calls the code index knowledge and every tracker a tracker', () => {
    expect(accountKind('code')).toBe('Knowledge');
    expect(accountKind('jira')).toBe('Tracker');
    expect(accountKind('confluence')).toBe('Tracker');
    expect(accountKind('github-issues')).toBe('Tracker');
    expect(accountKind('gitlab-issues')).toBe('Tracker');
  });
});

describe('hostOf', () => {
  it('shows the host of a URL', () => {
    expect(hostOf('https://acme.atlassian.net/wiki')).toBe('acme.atlassian.net');
  });

  it('falls back to the raw value when it is not a URL', () => {
    expect(hostOf('not a url')).toBe('not a url');
  });
});
```

`spire-ui/src/components/servingAccounts.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { ServingAccount } from '../api';
import { servingChip } from './servingAccounts';

const account = (state: ServingAccount['state'], name = 'TEST-bot'): ServingAccount => ({
  state,
  id: 'TEST-id',
  name,
  botUsername: 'test-bot',
  botAccountId: 'TEST-acct',
});

describe('servingChip', () => {
  it('is green only for ok', () => {
    expect(servingChip(account('ok'), null)).toMatchObject({ label: 'TEST-bot', pill: 'completed' });
  });

  it('is amber for the two half-registered states, and names the account', () => {
    expect(servingChip(account('no-identity'), null)).toMatchObject({ label: 'TEST-bot', pill: 'refused' });
    expect(servingChip(account('no-login'), null)).toMatchObject({ label: 'TEST-bot', pill: 'refused' });
  });

  it('is grey for disabled and for missing', () => {
    expect(servingChip(account('disabled'), null)).toMatchObject({ label: 'TEST-bot', pill: 'cancelled' });
    expect(servingChip(account('missing', ''), null)).toMatchObject({ label: 'none', pill: 'cancelled' });
  });

  /**
   * The server's state set can grow before this bundle does. A value the union does not list must
   * render grey and say so — never green, which is what a `refused` review once rendered as.
   */
  it('renders an unlisted state as unknown, never as ok', () => {
    const chip = servingChip({ ...account('ok'), state: 'brand-new' as ServingAccount['state'] }, null);
    expect(chip.pill).toBe('cancelled');
    expect(chip.label).toBe('unknown (brand-new)');
  });

  it('renders a failed lookup as unknown with the error as its title', () => {
    const chip = servingChip(undefined, 'Failed to load');
    expect(chip).toMatchObject({ label: 'unknown', pill: 'cancelled', title: 'Failed to load' });
  });

  it('renders a lookup still in flight as loading', () => {
    expect(servingChip(undefined, null).label).toBe('loading…');
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd spire-ui && npx vitest run src/components/accounts.test.ts src/components/servingAccounts.test.ts`
Expected: FAIL — modules not found.

- [ ] **Step 3: Extend `api.ts`**

Above `export interface ProviderView`, add:

```ts
/** REVIEWER posts comments and is the subject of the author allowlist; FACTORY pushes (ADR-038). */
export type ProviderRole = 'REVIEWER' | 'FACTORY';
```

In `ProviderView`, after `conversationLevel`, add:

```ts
  // Fixed at registration (a PUT that changes it is refused with 409). Arrives as runtime JSON: read it
  // through roleLabel(), which treats anything outside the union as unknown rather than as a reviewer.
  role: ProviderRole;
  // The login the forge resolved from the token — what a Factory push is authenticated as. The server
  // has always returned it; the type never declared it, so no screen could show it.
  botUsername: string | null;
```

In `ProviderInput`, after `conversationLevel`, add:

```ts
  role?: ProviderRole; // sent on create; on edit the stored role, never another — a change is a 409
```

After `verifyRepo` (before the `// ---- Webhook repositories` comment), add:

```ts
/**
 * Which registration serves one role for a (forge type, workspace). The set is closed on the server
 * and mirrored here; a reader must treat an unlisted value as unknown, never as `ok`.
 */
export type ServingState = 'ok' | 'no-identity' | 'no-login' | 'disabled' | 'missing';

export interface ServingAccount {
  state: ServingState;
  id: string | null; // null only for 'missing'
  name: string | null;
  botUsername: string | null;
  botAccountId: string | null;
}

export interface ServingAccounts {
  type: string;
  workspace: string;
  reviewer: ServingAccount;
  factory: ServingAccount;
}

// Which accounts would review and push for this forge + workspace, decided by the orchestrator's own
// resolvers. Nothing on a repository row stores this; the screen asks rather than infers.
export async function fetchServingAccounts(type: string, workspace: string): Promise<ServingAccounts> {
  const query = new URLSearchParams({ type, workspace });
  const res = await apiFetch(`/api/providers/serving?${query.toString()}`);
  if (!res.ok) return throwResponse(res, 'Failed to load the accounts serving this workspace');
  return res.json();
}
```

- [ ] **Step 4: Create `accounts.ts`**

```ts
/**
 * Pure helpers for the Accounts screen. In their own module so the list can be tested without
 * rendering, and so the role reader has exactly one implementation.
 */

/** A role arrives as JSON; anything outside the two known values is reported, not defaulted. */
export function roleLabel(role: string | null | undefined): string {
  switch (role) {
    case 'REVIEWER':
      return 'Reviewer';
    case 'FACTORY':
      return 'Factory';
    default:
      return `Unknown (${role ?? ''})`;
  }
}

/** The code index reads a repository; every other context source reads a tracker or a wiki. */
export function accountKind(contextType: string): 'Knowledge' | 'Tracker' {
  return contextType === 'code' ? 'Knowledge' : 'Tracker';
}

/** The host is what identifies a tracker account's scope; a value that is not a URL is shown as is. */
export function hostOf(baseUrl: string): string {
  try {
    return new URL(baseUrl).host;
  } catch {
    return baseUrl;
  }
}
```

- [ ] **Step 5: Create `servingAccounts.ts`**

```ts
import type { ServingAccount, ServingState } from '../api';

export interface ServingChip {
  label: string;
  pill: string; // an existing .pill tone: completed | refused | cancelled
  title: string;
}

/**
 * How each serving state reads, in the review pills' own tones. Amber (`refused`) for the two
 * half-registered states because each is a registration that exists and will not work as an
 * operator expects; grey for disabled and missing, whose cures differ but whose colour should not.
 */
const TONE: Record<ServingState, { pill: string; title: string }> = {
  ok: { pill: 'completed', title: 'Enabled and usable.' },
  'no-identity': {
    pill: 'refused',
    title:
      'Enabled, but the bot’s own identity is not resolved. It reviews, but cannot recognise its own ' +
      'comments, so conversation follow-ups are skipped. Re-save the account with a token the forge can identify.',
  },
  'no-login': {
    pill: 'refused',
    title:
      'Enabled, but no login is resolved, and a push is authenticated as one. POST /api/runs answers 409 ' +
      'until the account is re-saved with a token the forge can identify.',
  },
  disabled: { pill: 'cancelled', title: 'Registered, but disabled.' },
  missing: { pill: 'cancelled', title: 'No account is registered for this role.' },
};

/**
 * The chip for one role on one repository row. `undefined` with no failure is a lookup in flight;
 * a failure renders as unknown with the error as the title. An unlisted state is unknown too — grey,
 * named, and never green: the `refused` status once rendered as five green segments because the
 * reader defaulted into the success branch.
 */
export function servingChip(account: ServingAccount | undefined, failure: string | null): ServingChip {
  if (failure !== null) {
    return { label: 'unknown', pill: 'cancelled', title: failure };
  }
  if (account === undefined) {
    return { label: 'loading…', pill: 'cancelled', title: 'Loading' };
  }
  const tone = TONE[account.state];
  if (tone === undefined) {
    return {
      label: `unknown (${account.state})`,
      pill: 'cancelled',
      title: 'The server reported a state this dashboard does not know. Shown grey, never green.',
    };
  }
  if (account.state === 'missing') {
    return { label: 'none', pill: tone.pill, title: tone.title };
  }
  return { label: account.name ?? account.id ?? '?', pill: tone.pill, title: tone.title };
}
```

- [ ] **Step 6: Run the two pure tests**

Run: `cd spire-ui && npx vitest run src/components/accounts.test.ts src/components/servingAccounts.test.ts`
Expected: PASS.

- [ ] **Step 7: Make the fixtures compile**

Run: `cd spire-ui && npx tsc --noEmit`
Expected: errors of the form `Property 'role' is missing in type … but required in type 'ProviderView'` (and the same for `botUsername`). For each reported fixture add `role: 'REVIEWER',` and `botUsername: null,` after `conversationLevel`. Known sites: `src/components/SettingsProviders.form.test.tsx` (`existing`), `src/components/SettingsWebhookRepos.form.test.tsx` (`provider()`). Re-run `npx tsc --noEmit` until silent.

- [ ] **Step 8: Run the whole UI suite**

Run: `cd spire-ui && npm test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add spire-ui/src/api.ts spire-ui/src/components/accounts.ts spire-ui/src/components/accounts.test.ts \
        spire-ui/src/components/servingAccounts.ts spire-ui/src/components/servingAccounts.test.ts \
        spire-ui/src/components/SettingsProviders.form.test.tsx spire-ui/src/components/SettingsWebhookRepos.form.test.tsx
git commit -m "Type an account's role and the serving-accounts answer

ProviderView and ProviderInput gain role; ServingAccounts mirrors the
new endpoint. Two pure modules read them: roleLabel treats an unlisted
role as unknown rather than as a reviewer, and servingChip maps the
five states to the review pills' tones with an unlisted state grey,
never green."
```

(Add any further fixture files Step 7 touched to the `git add` line.)

---

### Task 5: Routes, redirects, titles, rail and attention labels

**Files:**
- Create: `spire-ui/src/components/RedirectKeepingQuery.tsx`
- Modify: `spire-ui/src/App.tsx` (`TITLES` 47-59, flags 71-79, rail 207-250, routes 372-376, imports)
- Modify: `spire-ui/src/components/AttentionBell.tsx:13-20`
- Test: `spire-ui/src/App.routes.test.tsx` (ROUTES 150-162, line 223-224, line 302, line 318, new redirect tests)
- Test: `spire-ui/src/components/AttentionBell.test.tsx` (+1 test near line 221)

**Interfaces:**
- Produces: routes `/settings/accounts`, `/settings/accounts/people`, `/settings/repositories`; redirects from `/settings/providers`, `/settings/operators`, `/settings/webhooks` preserving `location.search`. Screen components are unchanged in this task (`SettingsProviders` serves `/settings/accounts`, `SettingsOperators` serves `/settings/accounts/people`, `SettingsWebhookRepos` serves `/settings/repositories`).

- [ ] **Step 1: Change the routing test**

In `App.routes.test.tsx` `ROUTES`, replace the three rows

```ts
  { path: '/settings/operators', title: 'Operators', nav: 'Operators' },
  …
  { path: '/settings/providers', title: 'Repositories', nav: 'Repositories' },
  { path: '/settings/webhooks', title: 'Webhooks', nav: 'Webhooks' },
```

with

```ts
  { path: '/settings/accounts', title: 'Accounts', nav: 'Accounts' },
  { path: '/settings/accounts/people', title: 'Accounts', nav: 'Accounts' },
  …
  { path: '/settings/repositories', title: 'Repositories', nav: 'Repositories' },
```

(keep the other rows in place). At line 223-224 change `renderAt('/settings/providers')` → `renderAt('/settings/accounts')` and `toHaveTextContent('Repositories')` → `toHaveTextContent('Accounts')`. At line 302 change the viewer label list to `['General', 'Context', 'Accounts', 'Repositories', 'LLM', 'Prompts', 'Dead-letter']`. At line 318 change `'/settings/providers'` → `'/settings/accounts'`.

Add a location probe next to `renderAt`:

```tsx
/** Where the router ended up — for the redirect tests, which must see the query survive. */
function LocationProbe() {
  const location = useLocation();
  return <span data-testid="url">{location.pathname + location.search}</span>;
}

const renderAtWithProbe = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <LocationProbe />
    </MemoryRouter>,
  );
```

and `import { MemoryRouter, useLocation } from 'react-router';` at the top. Then add a new `describe` after `App — routing shell`:

```tsx
/**
 * The three renamed screens keep their old addresses working. Preserving the query is not
 * cosmetic: an attention row deep-links to one record with `?edit=<id>`, and the screens open it
 * through `useEditDeepLink` — a redirect that dropped the query would land on the page and open
 * nothing, which reads as the row having been fixed.
 */
describe('App — old settings routes redirect', () => {
  beforeEach(() => {
    session = ADMIN_SESSION;
    vi.stubGlobal('WebSocket', SilentSocket);
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockReturnValue({ matches: false, addEventListener: () => {}, removeEventListener: () => {} }),
    );
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(jsonResponse(payloadFor(url)))));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it.each([
    ['/settings/providers?edit=TEST-id-1', '/settings/accounts?edit=TEST-id-1', 'Accounts'],
    ['/settings/operators', '/settings/accounts/people', 'Accounts'],
    ['/settings/webhooks?edit=TEST-id-2', '/settings/repositories?edit=TEST-id-2', 'Repositories'],
  ])('sends %s to %s', async (from, to, title) => {
    renderAtWithProbe(from);

    await waitFor(() => expect(screen.getByTestId('url')).toHaveTextContent(to));
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(title);
  });
});
```

- [ ] **Step 2: Change the attention-bell test**

In `AttentionBell.test.tsx`, after the test `names each link by where it goes…` (ends ~line 244), add:

```tsx
  /**
   * The renamed screens label their rows by the new path, and a row still carrying the old path —
   * emitted by a service not yet upgraded — labels the same way rather than falling to "Open".
   */
  it('labels the Accounts and Repositories rows, old path or new', async () => {
    const renamed: AttentionItem = {
      code: 'WEBHOOK_DELIVERIES_REJECTED',
      severity: 'WARNING',
      subject: 'stub · TEST-OWNER/TEST-REPO',
      message: '1 webhook delivery was refused.',
      action: '/settings/repositories?edit=TEST-id-1',
      dismiss: null,
    };
    const legacy: AttentionItem = { ...renamed, message: 'Legacy row.', action: '/settings/webhooks?edit=TEST-id-9' };
    const accounts: AttentionItem = {
      code: 'SCM_PROVIDER_MISSING',
      severity: 'BLOCKING',
      subject: null,
      message: 'No enabled source-control provider is configured.',
      action: '/settings/accounts',
      dismiss: null,
    };
    await renderWithFeeds([accounts], [renamed, legacy]);
    await waitFor(() => screen.getByTestId('attention-count'));
    screen.getByTestId('attention-toggle').click();
    await waitFor(() => expect(screen.getByText(renamed.message)).toBeInTheDocument());

    expect(screen.getAllByRole('link', { name: 'Settings · Repositories' })).toHaveLength(2);
    expect(screen.getByRole('link', { name: 'Settings · Accounts' })).toHaveAttribute('href', '/settings/accounts');
  });
```

(If `AttentionItem.subject` is typed `string` rather than `string | null`, use `subject: ''`; check `api.ts` `AttentionItem` at ~line 963.)

- [ ] **Step 3: Run both test files to verify they fail**

Run: `cd spire-ui && npx vitest run src/App.routes.test.tsx src/components/AttentionBell.test.tsx`
Expected: FAIL — the new routes render "Dashboard", the redirects do not happen, the labels fall to "Open".

- [ ] **Step 4: Create `RedirectKeepingQuery.tsx`**

```tsx
import { Navigate, useLocation } from 'react-router';

/**
 * A route that moved. `Navigate` alone drops the query, and the query is load-bearing here: the
 * attention panel deep-links to one record with `?edit=<id>`, which the settings screens consume
 * through `useEditDeepLink`. `replace` keeps the dead address out of history so Back does not walk
 * into a second redirect.
 */
export default function RedirectKeepingQuery({ to }: { to: string }) {
  const { search } = useLocation();
  return <Navigate to={{ pathname: to, search }} replace />;
}
```

- [ ] **Step 5: Edit `App.tsx`**

Imports: add `import RedirectKeepingQuery from './components/RedirectKeepingQuery';`. Keep `UsersRound` (it moves to Accounts).

`TITLES`: replace the three rows `['/settings/operators', 'Operators']`, `['/settings/providers', 'Repositories']`, `['/settings/webhooks', 'Webhooks']` with, in place of the operators row:

```ts
  ['/settings/accounts', 'Accounts'],
```

and in place of the webhooks row:

```ts
  ['/settings/repositories', 'Repositories'],
```

Flags: replace `onProviders`, `onWebhooks`, `onOperators` with

```ts
  const onAccounts = location.pathname.startsWith('/settings/accounts');
  const onRepositories = location.pathname.startsWith('/settings/repositories');
```

Rail (lines 207-250): the Operators anchor becomes

```tsx
          <a className={onAccounts ? 'active' : ''} href="#/settings/accounts">
            <UsersRound className="ic" size={16} />
            Accounts
          </a>
```

Delete the `href="#/settings/providers"` anchor (the one with the three-circle branch glyph, lines 231-240) **but keep its `<svg>`**: paste that svg into the Webhooks anchor in place of the hook glyph, and change that anchor to

```tsx
          <a className={onRepositories ? 'active' : ''} href="#/settings/repositories">
            <svg className="ic" viewBox="0 0 16 16" fill="none">
              <circle cx="4" cy="3.5" r="1.9" stroke="currentColor" strokeWidth="1.4" />
              <circle cx="4" cy="12.5" r="1.9" stroke="currentColor" strokeWidth="1.4" />
              <circle cx="12" cy="3.5" r="1.9" stroke="currentColor" strokeWidth="1.4" />
              <path d="M4 5.4v5.2" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
              <path d="M12 5.4c0 3.4-3.5 3.6-6 5.1" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
            </svg>
            Repositories
          </a>
```

Routes (lines 372-376): replace

```tsx
          <Route path="/settings/operators" element={configure(<SettingsOperators />)} />
          …
          <Route path="/settings/providers" element={configure(<SettingsProviders />)} />
          <Route path="/settings/webhooks" element={configure(<SettingsWebhookRepos />)} />
```

with

```tsx
          <Route path="/settings/accounts" element={configure(<SettingsProviders />)} />
          <Route path="/settings/accounts/people" element={configure(<SettingsOperators />)} />
          <Route path="/settings/repositories" element={configure(<SettingsWebhookRepos />)} />
          {/* The three screens moved on 2026-09-07. Old addresses live in bookmarks and in attention
              rows emitted by a service not yet upgraded; the query rides along because ?edit=<id> is
              what opens the named record. */}
          <Route path="/settings/providers" element={<RedirectKeepingQuery to="/settings/accounts" />} />
          <Route path="/settings/operators" element={<RedirectKeepingQuery to="/settings/accounts/people" />} />
          <Route path="/settings/webhooks" element={<RedirectKeepingQuery to="/settings/repositories" />} />
```

(keep `/settings/memory` and `/settings/general` where they are.)

- [ ] **Step 6: Edit `AttentionBell.tsx` `ACTION_LABELS`**

```ts
const ACTION_LABELS: Record<string, string> = {
  '/settings/repositories': 'Settings · Repositories',
  '/settings/accounts': 'Settings · Accounts',
  // The two addresses these screens had until 2026-09-07. A row emitted by a not-yet-upgraded
  // service still carries them; it must read as the screen it lands on, not as "Open".
  '/settings/webhooks': 'Settings · Repositories',
  '/settings/providers': 'Settings · Accounts',
  '/settings/llm': 'Settings · LLM',
  '/settings/context': 'Settings · Context',
  '/settings/dlq': 'Dead-letter',
  '/': 'Reviews',
};
```

Update the existing test at `AttentionBell.test.tsx:238` accordingly: the link named `'Settings · Webhooks'` is now named `'Settings · Repositories'`.

- [ ] **Step 7: Run the two test files, then the suite and the type check**

Run: `cd spire-ui && npx vitest run src/App.routes.test.tsx src/components/AttentionBell.test.tsx && npm test && npx tsc --noEmit`
Expected: PASS, PASS, silent.

- [ ] **Step 8: Commit**

```bash
git add spire-ui/src/App.tsx spire-ui/src/App.routes.test.tsx spire-ui/src/components/RedirectKeepingQuery.tsx \
        spire-ui/src/components/AttentionBell.tsx spire-ui/src/components/AttentionBell.test.tsx
git commit -m "Route Accounts and Repositories, and redirect the old addresses

The bot-token registry was labelled Repositories and the per-repository
webhook registry was labelled Webhooks. The first is now Accounts, with
Operators as its People tab; the second is Repositories. The three old
routes redirect and keep their query, because ?edit=<id> is what opens
the record an attention row names. The bell labels both old and new
paths so a row from a not-yet-upgraded service still reads correctly."
```

---

### Task 6: The Accounts tab strip, and the People tab

**Files:**
- Create: `spire-ui/src/components/AccountsTabs.tsx`
- Modify: `spire-ui/src/index.css` (append near `.prov-head`, ~line 897)
- Modify: `spire-ui/src/components/SettingsProviders.tsx` (render the strip; `<h2>` text)
- Modify: `spire-ui/src/components/SettingsOperators.tsx` (render the strip)
- Test: `spire-ui/src/components/AccountsTabs.test.tsx` (create)
- Test: `spire-ui/src/components/SettingsOperators.test.tsx` (+1)

**Interfaces:**
- Produces: `AccountsTabs({ active }: { active: 'machine' | 'people' })` — plain anchors (`href="#/settings/accounts"`, `href="#/settings/accounts/people"`), `aria-current="page"` on the active one. No router hooks, so both screens keep rendering in tests that mount them without a router.

- [ ] **Step 1: Write the failing tests**

`AccountsTabs.test.tsx`:

```tsx
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import AccountsTabs from './AccountsTabs';

/**
 * Plain anchors on purpose: `SettingsOperators` is rendered without a router in its own tests and
 * in the People tab under one, and the strip must work in both. The hash hrefs are what the rail
 * uses, so the two navigate the same way.
 */
describe('AccountsTabs', () => {
  it('offers both tabs and marks the active one as the current page', () => {
    render(<AccountsTabs active="people" />);
    const machine = screen.getByRole('link', { name: 'Machine accounts' });
    const people = screen.getByRole('link', { name: 'People' });
    expect(machine).toHaveAttribute('href', '#/settings/accounts');
    expect(people).toHaveAttribute('href', '#/settings/accounts/people');
    expect(people).toHaveAttribute('aria-current', 'page');
    expect(machine).not.toHaveAttribute('aria-current');
  });

  it('marks Machine accounts when that tab is active', () => {
    render(<AccountsTabs active="machine" />);
    expect(screen.getByRole('link', { name: 'Machine accounts' })).toHaveAttribute('aria-current', 'page');
  });
});
```

In `SettingsOperators.test.tsx`, add inside the `describe`:

```tsx
  /** The screen is the People tab of Accounts, and says so above its own content. */
  it('renders as the People tab of Accounts', async () => {
    vi.spyOn(api, 'fetchOperatorIdentities').mockResolvedValue([]);

    render(<SettingsOperators />);

    await waitFor(() => expect(screen.getByRole('link', { name: 'People' })).toHaveAttribute('aria-current', 'page'));
    expect(screen.getByRole('link', { name: 'Machine accounts' })).toHaveAttribute('href', '#/settings/accounts');
  });
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd spire-ui && npx vitest run src/components/AccountsTabs.test.tsx src/components/SettingsOperators.test.tsx`
Expected: FAIL — module not found; no link named People.

- [ ] **Step 3: Create `AccountsTabs.tsx`**

```tsx
/**
 * The two tabs of Settings → Accounts. Machine accounts hold a token and a role; People are the
 * operators the identity provider knows and the SCM accounts they proved are theirs. They share a
 * page and never a table — one mixed list is the shape ADR-038 exists to prevent.
 *
 * <p>Plain anchors with the rail's hash hrefs rather than router links: `SettingsOperators` is
 * mounted without a router in its own tests, and a tab strip must not be the reason a screen needs
 * one.
 */
export type AccountsTab = 'machine' | 'people';

export default function AccountsTabs({ active }: { active: AccountsTab }) {
  return (
    <nav className="tabs" aria-label="Accounts">
      <a
        className={active === 'machine' ? 'tab active' : 'tab'}
        href="#/settings/accounts"
        aria-current={active === 'machine' ? 'page' : undefined}
      >
        Machine accounts
      </a>
      <a
        className={active === 'people' ? 'tab active' : 'tab'}
        href="#/settings/accounts/people"
        aria-current={active === 'people' ? 'page' : undefined}
      >
        People
      </a>
    </nav>
  );
}
```

- [ ] **Step 4: Add the CSS**

In `index.css`, immediately before the `.prov-head {` rule (~line 897), add (2-space indent, matching the file):

```css
  /* Settings → Accounts: two tabs above the card. The first tab strip in the app; kept to anchors. */
  .tabs { display: flex; gap: 4px; margin: 0 0 14px; }
  .tab { padding: 6px 12px; border-radius: 8px; font-size: 13px; color: var(--text-2); text-decoration: none; border: 1px solid transparent; }
  .tab:hover { color: var(--text); }
  .tab.active { color: var(--text); background: var(--panel); border-color: var(--border); }
```

- [ ] **Step 5: Render the strip on both screens**

`SettingsProviders.tsx`: `import AccountsTabs from './AccountsTabs';`; in the returned JSX, directly inside `<section className="content">` and before `<div className="card">`, add `<AccountsTabs active="machine" />`. Change `<h2 className="prov-title">Repositories</h2>` to `<h2 className="prov-title">Accounts</h2>`.

`SettingsOperators.tsx`: `import AccountsTabs from './AccountsTabs';`; directly inside `<section className="content">` and before `<ScmConnections />`, add `<AccountsTabs active="people" />`.

- [ ] **Step 6: Run the tests, the contract test and the suite**

Run: `cd spire-ui && npx vitest run src/components/AccountsTabs.test.tsx src/components/SettingsOperators.test.tsx src/styles.contract.test.ts && npm test && npx tsc --noEmit`
Expected: PASS (the contract test finds `.tabs`; `tab active` is an expression, not a literal, but `.tab` and `.tab.active` are defined anyway).

- [ ] **Step 7: Commit**

```bash
git add spire-ui/src/components/AccountsTabs.tsx spire-ui/src/components/AccountsTabs.test.tsx \
        spire-ui/src/components/SettingsProviders.tsx spire-ui/src/components/SettingsOperators.tsx \
        spire-ui/src/components/SettingsOperators.test.tsx spire-ui/src/index.css
git commit -m "Give Accounts two tabs: Machine accounts and People

Machine accounts hold a token and a role; people are operators the
identity provider knows. They share a page, never a table. The strip is
plain anchors so a screen rendered without a router keeps working."
```

---

### Task 7: Split the accounts screen into page, table and form (no behaviour change)

**Files:**
- Modify: `spire-ui/src/components/SettingsProviders.tsx` (585 lines → the page only)
- Create: `spire-ui/src/components/AccountsTable.tsx` (the table + `ConnCell`)
- Create: `spire-ui/src/components/ProviderFormModal.tsx` (`ProviderFormModal` + `DeleteConfirmModal`)
- Test: none new — `SettingsProviders.form.test.tsx`, `SettingsProviders.test.ts`, `App.routes.test.tsx` must stay green unchanged.

**Interfaces:**
- Produces: `AccountsTable({ providers, conns, onRecheck, onEdit, onDelete }: { providers: ProviderView[]; conns: Record<string, Conn>; onRecheck: (id: string) => void; onEdit: (p: ProviderView) => void; onDelete: (p: ProviderView) => void })` as default export, plus `export interface Conn { state: ConnState; account?: string | null; detail?: string | null }` and `export type ConnState = 'idle' | 'checking' | 'ok' | 'fail'` moved here. `ProviderFormModal` (default export) and `DeleteConfirmModal` (named export) with their existing props. `conversationLabel` and `CONVERSATION_OPTIONS` move to `ProviderFormModal.tsx`; `SettingsProviders.tsx` re-exports `conversationLabel` (`export { conversationLabel } from './ProviderFormModal';`) so `SettingsProviders.test.ts` and `AccountsTable` keep importing it from where they do.

- [ ] **Step 1: Run the tests you must keep green, to record the baseline**

Run: `cd spire-ui && npx vitest run src/components/SettingsProviders.form.test.tsx src/components/SettingsProviders.test.ts src/App.routes.test.tsx`
Expected: PASS. Note the counts.

- [ ] **Step 2: Create `ProviderFormModal.tsx`**

Move, verbatim, from `SettingsProviders.tsx`: the `CONVERSATION_OPTIONS` constant, `conversationLabel`, `PROVIDER_TYPES`, `DEFAULT_BASE_URLS`, `BEARER_ONLY`, `KNOWN_DEFAULTS`, `DEFAULT_BASE_URL`, the whole `ProviderFormModal` function (make it `export default function ProviderFormModal`), and the whole `DeleteConfirmModal` function (make it `export function DeleteConfirmModal`). Imports the file needs:

```tsx
import { useState } from 'react';
import {
  createProvider,
  deleteProvider,
  updateProvider,
  type AuthKind,
  type ProviderInput,
  type ProviderView,
} from '../api';
import Select from './Select';
```

Give `conversationLabel` `export` (it already has it). Add a file-level JSDoc:

```tsx
/**
 * The add/edit dialog and the delete confirmation for a forge account. Extracted from the page so
 * each file has one job and stays under the 250-line guideline; behaviour is unchanged.
 */
```

- [ ] **Step 3: Create `AccountsTable.tsx`**

Move the `ConnState` type, `Conn` interface (both `export`ed now) and the `ConnCell` function verbatim. Add the table as a component that receives what the page owns:

```tsx
import { type ProviderView } from '../api';
import IconButton from './IconButton';
import LastChecked from './LastCheckedBadge';
import { conversationLabel } from './ProviderFormModal';

// Per-provider connectivity status, keyed by provider id.
export type ConnState = 'idle' | 'checking' | 'ok' | 'fail';
export interface Conn {
  state: ConnState;
  account?: string | null;
  detail?: string | null;
}

interface Props {
  providers: ProviderView[];
  conns: Record<string, Conn>;
  onRecheck: (id: string) => void;
  onEdit: (p: ProviderView) => void;
  onDelete: (p: ProviderView) => void;
}

/** The machine-accounts table. The page owns loading and the modals; this only renders rows. */
export default function AccountsTable({ providers, conns, onRecheck, onEdit, onDelete }: Props) {
  return (
    <table className="prov-table">
      {/* the existing <thead> and <tbody> from SettingsProviders, with:
            onClick={() => setForm(p)}          → onClick={() => onEdit(p)}
            onClick={() => setConfirmDelete(p)} → onClick={() => onDelete(p)}
            onRecheck={() => void checkOne(p.id)} → onRecheck={() => onRecheck(p.id)} */}
    </table>
  );
}
```

(Paste the real `<thead>`/`<tbody>` — the comment above only names the three substitutions.) Then the `ConnCell` function below it, unchanged.

- [ ] **Step 4: Reduce `SettingsProviders.tsx` to the page**

It keeps: imports (`useEffect`, `useState`, `checkProvider`, `fetchProviders`, `type ProviderView`, `Tooltip`, `useEditDeepLink`, `AccountsTabs`), the `SettingsProviders` component with its state, `checkOne`, `load`, the `<section>` (tabs, card head, error/loading/empty states, and `<AccountsTable … />` in place of the inline table), and the two modals wired as before:

```tsx
import AccountsTable, { type Conn } from './AccountsTable';
import ProviderFormModal, { DeleteConfirmModal } from './ProviderFormModal';

export { conversationLabel } from './ProviderFormModal';
```

The table call:

```tsx
          <AccountsTable
            providers={providers}
            conns={conns}
            onRecheck={(id) => void checkOne(id)}
            onEdit={setForm}
            onDelete={setConfirmDelete}
          />
```

- [ ] **Step 5: Run the baseline tests, the type check and the line counts**

Run: `cd spire-ui && npx vitest run src/components/SettingsProviders.form.test.tsx src/components/SettingsProviders.test.ts src/App.routes.test.tsx && npx tsc --noEmit && wc -l src/components/SettingsProviders.tsx src/components/AccountsTable.tsx src/components/ProviderFormModal.tsx`
Expected: the same PASS counts as Step 1; `tsc` silent; the page well under 250 lines, the form under ~300 (its remaining overage is recorded in Task 9).

- [ ] **Step 6: Commit**

```bash
git add spire-ui/src/components/SettingsProviders.tsx spire-ui/src/components/AccountsTable.tsx spire-ui/src/components/ProviderFormModal.tsx
git commit -m "Split the accounts screen into page, table and form

A mechanical extraction ahead of the changes the next two tasks make to
the table and the form. No behaviour changes; the existing tests pass
unchanged."
```

---

### Task 8: The Machine accounts list — Kind, Role, Identity, Scope, and tracker rows

**Files:**
- Modify: `spire-ui/src/components/AccountsTable.tsx` (columns; tracker rows; new props)
- Modify: `spire-ui/src/components/SettingsProviders.tsx` (load tracker rows; "Add account"; empty state)
- Modify: `spire-ui/src/components/SettingsProviders.form.test.tsx` (`openAddForm` / `submit` regexes; mock `fetchContextProviders`)
- Modify: `spire-ui/src/App.routes.test.tsx:318-329` (`/add provider/i` → `/add account/i`)
- Test: `spire-ui/src/components/SettingsProviders.accounts.test.tsx` (create)

**Interfaces:**
- Consumes: `roleLabel`, `accountKind`, `hostOf` (Task 4, `./accounts`); `fetchContextProviders`, `ContextProviderView` (`../api`); `ProviderView.role`, `ProviderView.botUsername` (Task 4).
- Produces: `AccountsTable` props gain `trackers: ContextProviderView[]`. Columns, in order: Name · Kind · Role · Identity · Scope · Connection · Enabled · May command · Conversation · actions. The header button and the empty-state button are both named **Add account**.

- [ ] **Step 1: Write the failing list test**

`SettingsProviders.accounts.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsProviders from './SettingsProviders';
import * as api from '../api';

const renderPage = () =>
  render(
    <MemoryRouter>
      <SettingsProviders />
    </MemoryRouter>,
  );

const forge = (over: Partial<api.ProviderView>): api.ProviderView => ({
  id: 'TEST-p1',
  name: 'TEST reviewer',
  type: 'github',
  baseUrl: 'https://api.github.com',
  workspace: 'TEST-acme',
  authKind: 'bearer',
  authUsername: null,
  hasSecret: true,
  botAccountId: 'TEST-acct-1',
  botUsername: 'test-reviewer',
  enabled: true,
  authors: ['TEST-1', 'TEST-2'],
  conversationLevel: 'EXPLAIN',
  role: 'REVIEWER',
  createdAt: '2026-09-07T00:00:00Z',
  lastCheckAt: null,
  lastCheckOk: null,
  lastCheckError: null,
  ...over,
});

const tracker: api.ContextProviderView = {
  id: 'TEST-c1',
  name: 'TEST Jira',
  type: 'jira',
  baseUrl: 'https://test-acme.atlassian.net',
  authKind: 'basic',
  username: 'jira-bot@example.invalid',
  projectKeys: null,
  hasSecret: true,
  enabled: true,
  isDefault: false,
  createdAt: '2026-09-07T00:00:00Z',
  lastCheckAt: null,
  lastCheckOk: null,
  lastCheckError: null,
};

const rowNamed = (name: string) => screen.getByText(name).closest('tr') as HTMLElement;

describe('SettingsProviders — the Machine accounts list', () => {
  beforeEach(() => {
    vi.spyOn(api, 'checkProvider').mockResolvedValue({ ok: true, account: 'test-reviewer', detail: null });
  });

  it('is titled Accounts and shows a forge row with its kind, role, identity and scope', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({})]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Accounts' })).toBeInTheDocument();
    const row = rowNamed('TEST reviewer');
    expect(within(row).getByText('Forge · github')).toBeInTheDocument();
    expect(within(row).getByText('Reviewer')).toBeInTheDocument();
    expect(within(row).getByText('@test-reviewer')).toBeInTheDocument();
    expect(within(row).getByText('TEST-acme')).toBeInTheDocument();
    expect(within(row).getByText('2')).toBeInTheDocument(); // May command: two ids listed
    expect(within(row).getByText('Explain')).toBeInTheDocument();
  });

  /** A factory account has no allowlist and no conversation: those are the reviewer's job. */
  it('shows a factory row with dashes where the reviewer-only columns are', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([
      forge({ id: 'TEST-p2', name: 'TEST factory', role: 'FACTORY', botUsername: 'test-factory', authors: [] }),
    ]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    const row = rowNamed(await screen.findByText('TEST factory').then((el) => el.textContent ?? ''));
    expect(within(row).getByText('Factory')).toBeInTheDocument();
    expect(within(row).getByText('@test-factory')).toBeInTheDocument();
    expect(within(row).getAllByText('—')).toHaveLength(2);
  });

  it('says when the identity is not resolved rather than showing nothing', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({ botUsername: null, botAccountId: '' })]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    expect(within(rowNamed(await screen.findByText('TEST reviewer').then((el) => el.textContent ?? ''))).getByText('not resolved')).toBeInTheDocument();
  });

  /** Tracker accounts are listed so one screen answers "who acts as what"; they are edited on Context. */
  it('lists tracker accounts read-only, after the forge rows, with a link to manage them on Context', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({})]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([tracker]);
    renderPage();

    const row = rowNamed(await screen.findByText('TEST Jira').then((el) => el.textContent ?? ''));
    expect(within(row).getByText('Tracker · jira')).toBeInTheDocument();
    expect(within(row).getByText('Read')).toBeInTheDocument();
    expect(within(row).getByText('jira-bot@example.invalid')).toBeInTheDocument();
    expect(within(row).getByText('test-acme.atlassian.net')).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: 'Manage on Context' })).toHaveAttribute(
      'href',
      '#/settings/context?edit=TEST-c1',
    );
    expect(within(row).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();

    const rows = screen.getAllByRole('row').slice(1); // drop the header
    expect(rows[0]).toHaveTextContent('TEST reviewer');
    expect(rows[1]).toHaveTextContent('TEST Jira');
  });

  /** The forge list is the one that matters; a tracker fetch failing must not blank it. */
  it('keeps the forge rows when the tracker list cannot be loaded, and says so', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([forge({})]);
    vi.spyOn(api, 'fetchContextProviders').mockRejectedValue(new Error('Failed to load context providers'));
    renderPage();

    expect(await screen.findByText('TEST reviewer')).toBeInTheDocument();
    expect(await screen.findByText(/tracker accounts could not be loaded/i)).toBeInTheDocument();
  });

  it('shows the empty state only when there is nothing of either kind, and offers Add account', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText(/no machine accounts yet/i)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /add account/i }).length).toBeGreaterThanOrEqual(1);
    await waitFor(() => expect(screen.queryByRole('button', { name: /add provider/i })).not.toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd spire-ui && npx vitest run src/components/SettingsProviders.accounts.test.tsx`
Expected: FAIL on the heading (still "Repositories" if Task 6 was skipped, else on 'Forge · github').

- [ ] **Step 3: Extend `AccountsTable.tsx`**

Replace the component (keep `ConnCell` as is):

```tsx
import { type ContextProviderView, type ProviderView } from '../api';
import IconButton from './IconButton';
import LastChecked from './LastCheckedBadge';
import { accountKind, hostOf, roleLabel } from './accounts';
import { conversationLabel } from './ProviderFormModal';

// (ConnState / Conn unchanged)

interface Props {
  providers: ProviderView[];
  trackers: ContextProviderView[];
  conns: Record<string, Conn>;
  onRecheck: (id: string) => void;
  onEdit: (p: ProviderView) => void;
  onDelete: (p: ProviderView) => void;
}

/**
 * The machine-accounts table: forge accounts first (edited here), then tracker and knowledge
 * accounts (read-only, managed on Context). One list answers "who acts as what"; two registries
 * still back it, which is why the second kind links out rather than opening a form here.
 *
 * <p>Reviewer-only columns show a dash on a Factory row. The allowlist and the conversation level
 * are read through the REVIEWER lookup alone, so on a factory row they are dead data, and showing
 * a number there would invite editing it.
 */
export default function AccountsTable({ providers, trackers, conns, onRecheck, onEdit, onDelete }: Props) {
  const mono = { fontSize: 12, color: 'var(--text-2)' } as const;
  return (
    <table className="prov-table">
      <thead>
        <tr>
          <th>Name</th>
          <th>Kind</th>
          <th>Role</th>
          <th>Identity</th>
          <th>Scope</th>
          <th>Connection</th>
          <th>Enabled</th>
          <th className="cell-r">May command</th>
          <th>Conversation</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {providers.map((p) => (
          <tr key={p.id}>
            <td>
              <div className="prov-name">{p.name}</div>
              <div className="prov-sub">{p.baseUrl}</div>
            </td>
            <td className="mono" style={mono}>{`Forge · ${p.type}`}</td>
            <td>{roleLabel(p.role)}</td>
            <td className="mono" style={mono}>{identityOf(p)}</td>
            <td className="mono" style={mono}>{p.workspace}</td>
            <td>
              <ConnCell conn={conns[p.id]} enabled={p.enabled} onRecheck={() => onRecheck(p.id)} />
              <LastChecked item={p} />
            </td>
            <td>
              <span className={`pill ${p.enabled ? 'completed' : 'cancelled'}`}>
                <span className="glyph"></span>
                {p.enabled ? 'Enabled' : 'Disabled'}
              </span>
            </td>
            <td className="cell-r mono" style={mono}>{p.role === 'REVIEWER' ? p.authors.length : '—'}</td>
            <td>
              <span className="prov-sub">{p.role === 'REVIEWER' ? conversationLabel(p.conversationLevel) : '—'}</span>
            </td>
            <td>
              <div className="prov-actions">
                <IconButton kind="edit" onClick={() => onEdit(p)} title="Edit" aria-label="Edit" />
                <IconButton kind="delete" onClick={() => onDelete(p)} title="Delete" aria-label="Delete" />
              </div>
            </td>
          </tr>
        ))}
        {trackers.map((t) => (
          <tr key={`context-${t.id}`}>
            <td>
              <div className="prov-name">{t.name}</div>
              <div className="prov-sub">{t.baseUrl}</div>
            </td>
            <td className="mono" style={mono}>{`${accountKind(t.type)} · ${t.type}`}</td>
            <td>Read</td>
            <td className="mono" style={mono}>{t.username ?? '—'}</td>
            <td className="mono" style={mono}>{hostOf(t.baseUrl)}</td>
            <td>
              <LastChecked item={t} />
            </td>
            <td>
              <span className={`pill ${t.enabled ? 'completed' : 'cancelled'}`}>
                <span className="glyph"></span>
                {t.enabled ? 'Enabled' : 'Disabled'}
              </span>
            </td>
            <td className="cell-r mono" style={mono}>—</td>
            <td>
              <span className="prov-sub">—</span>
            </td>
            <td>
              <div className="prov-actions">
                <a className="btn-ghost" href={`#/settings/context?edit=${encodeURIComponent(t.id)}`}>
                  Manage on Context
                </a>
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The login the forge knows the bot by; the id when only that resolved; a plain word when neither did. */
function identityOf(p: ProviderView): string {
  if (p.botUsername) return `@${p.botUsername}`;
  if (p.botAccountId) return p.botAccountId;
  return 'not resolved';
}
```

If `LastChecked`'s `item` prop is typed to `ProviderView`, widen it to `Pick<ProviderView, 'lastCheckAt' | 'lastCheckOk' | 'lastCheckError'>` in `LastCheckedBadge.tsx` (the Context screen already passes a `ContextProviderView`, so it is most likely structural already — check before editing).

- [ ] **Step 4: Extend the page**

In `SettingsProviders.tsx`:

```tsx
import { checkProvider, fetchContextProviders, fetchProviders, type ContextProviderView, type ProviderView } from '../api';
…
  const [trackers, setTrackers] = useState<ContextProviderView[]>([]);
  const [trackerError, setTrackerError] = useState<string | null>(null);
```

(that makes eight `useState` calls in this component — the cap, not over it.) In `load()`, after the existing `try/catch/finally`, add:

```tsx
    // Tracker and knowledge accounts are listed read-only. Loaded separately so a failure there
    // cannot take the forge list with it: the forge list is the one a review depends on.
    try {
      setTrackers(await fetchContextProviders());
      setTrackerError(null);
    } catch (err) {
      setTrackerError(err instanceof Error ? err.message : String(err));
    }
```

Rename both buttons: the header `Tooltip label="Add account"` / `aria-label="Add account"`, and the empty-state button text `Add account`. Empty state condition and copy:

```tsx
        ) : providers.length === 0 && trackers.length === 0 ? (
          <div className="prov-empty">
            <span>No machine accounts yet. Add a reviewer account to start reviewing.</span>
            <button className="btn" onClick={() => setForm('new')}>
              …svg unchanged…
              Add account
            </button>
          </div>
        ) : (
          <>
            {trackerError && (
              <p className="prov-note">Tracker accounts could not be loaded: {trackerError}</p>
            )}
            <AccountsTable
              providers={providers}
              trackers={trackers}
              conns={conns}
              onRecheck={(id) => void checkOne(id)}
              onEdit={setForm}
              onDelete={setConfirmDelete}
            />
          </>
        )}
```

- [ ] **Step 5: Update the tests that name the button**

`SettingsProviders.form.test.tsx`: `openAddForm` → `name: /add account/i`; `submit` → `/^(add account|save changes)$/i` (the modal's submit is renamed in Task 9 — until then the `add provider` alternative must stay: use `/^(add provider|add account|save changes)$/i` now and tighten it in Task 9). In its `beforeEach` add `vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);`.

`App.routes.test.tsx:318-329`: `queryByRole('button', { name: /add provider/i })` → `/add account/i`.

- [ ] **Step 6: Run the tests and the type check**

Run: `cd spire-ui && npx vitest run src/components/SettingsProviders.accounts.test.tsx src/components/SettingsProviders.form.test.tsx src/App.routes.test.tsx && npx tsc --noEmit`
Expected: PASS, silent.

- [ ] **Step 7: Commit**

```bash
git add spire-ui/src/components/AccountsTable.tsx spire-ui/src/components/SettingsProviders.tsx \
        spire-ui/src/components/SettingsProviders.accounts.test.tsx spire-ui/src/components/SettingsProviders.form.test.tsx \
        spire-ui/src/App.routes.test.tsx
git commit -m "List every machine account with its kind, role and identity

Forge accounts show Kind, Role, the login the forge knows them by and
the workspace they serve; reviewer-only columns are dashes on a Factory
row. Tracker and knowledge accounts appear read-only after them with a
link to Context, so one screen answers who acts as what while two
registries still back it."
```

(Add `spire-ui/src/components/LastCheckedBadge.tsx` if Step 3 widened its prop.)

---

### Task 9: The account form — a role, fixed after registration

**Files:**
- Modify: `spire-ui/src/components/ProviderFormModal.tsx`
- Modify: `spire-ui/src/components/SettingsProviders.form.test.tsx`
- Create: `techdebt/spire-ui/4-3-the-account-form-holds-thirteen-state-hooks.md`

**Interfaces:**
- Consumes: `ProviderRole`, `ProviderInput.role` (Task 4); `roleLabel` (`./accounts`).
- Produces: the form sends `role` on create (the selected value, `REVIEWER` by default) and `initial.role` on edit. With role `FACTORY` the Conversation level and May command fields are not rendered. Dialog titles: **Add account** / **Edit account** / **Delete account**; submit button **Add account** / **Save changes**.

- [ ] **Step 1: Write the failing form tests**

In `SettingsProviders.form.test.tsx`, tighten `submit` to `/^(add account|save changes)$/i`, and add inside the `describe`:

```tsx
  /**
   * The form can express the FACTORY role, because the 409 from POST /api/runs sends the operator
   * here. Asserted on what reaches the API rather than on the control: a form that omits the field
   * sends a payload the server reads as REVIEWER, and both look identical on screen.
   */
  it('sends the FACTORY role the machine account needs', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(existing);
    renderPage();
    const dialog = await openFilledAddForm();
    typeSecret(dialog);

    fireEvent.click(within(dialog).getByRole('combobox', { name: /^role$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'Factory' }));
    submit(dialog);

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0].role).toBe('FACTORY');
  });

  it('defaults a new account to REVIEWER and sends that explicitly', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(existing);
    renderPage();
    const dialog = await openFilledAddForm();
    typeSecret(dialog);
    submit(dialog);

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0].role).toBe('REVIEWER');
  });

  /** A role is fixed at registration; the edit form shows it and sends it back unchanged. */
  it('shows the stored role read-only on edit and sends it back, never another', async () => {
    const factory: api.ProviderView = { ...existing, id: 'prov-2', name: 'Acme Factory', role: 'FACTORY' };
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([factory]);
    const update = vi.spyOn(api, 'updateProvider').mockResolvedValue(factory);
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /^edit$/i }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByRole('combobox', { name: /^role$/i })).not.toBeInTheDocument();
    expect(within(dialog).getByText('Factory')).toBeInTheDocument();
    expect(within(dialog).getByText(/set at registration/i)).toBeInTheDocument();
    submit(dialog);

    await waitFor(() => expect(update).toHaveBeenCalled());
    expect(update.mock.calls[0][1].role).toBe('FACTORY');
  });

  /** The allowlist and the conversation level are the reviewer's; a factory account has neither. */
  it('hides the reviewer-only fields once Factory is chosen', async () => {
    renderPage();
    const dialog = await openFilledAddForm();
    expect(within(dialog).getByText(/may command this bot/i)).toBeInTheDocument();
    expect(within(dialog).getByRole('combobox', { name: /conversation level/i })).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole('combobox', { name: /^role$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'Factory' }));

    expect(within(dialog).queryByText(/may command this bot/i)).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('combobox', { name: /conversation level/i })).not.toBeInTheDocument();
    expect(within(dialog).getByText(/must resolve to a login/i)).toBeInTheDocument();
  });

  /** /fix matches the stable id only; a field that says "username" leads to a list /fix refuses. */
  it('asks for a stable user id in the allowlist, and flushes a typed one on submit', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(existing);
    renderPage();
    const dialog = await openFilledAddForm();
    typeSecret(dialog);

    const field = within(dialog).getByPlaceholderText('stable user id');
    fireEvent.change(field, { target: { value: '3218389' } });
    submit(dialog);

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0].authors).toEqual(['3218389']);
  });
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd spire-ui && npx vitest run src/components/SettingsProviders.form.test.tsx`
Expected: the five new tests FAIL (no Role combobox; placeholder is `username`; submit button not found by the tightened regex).

- [ ] **Step 3: Rework the form**

In `ProviderFormModal.tsx`:

Imports: add `type ProviderRole` to the `../api` import; `import { roleLabel } from './accounts';`.

Add above the component:

```tsx
/** The fields only a REVIEWER has, held together: a Factory row has none of them. */
interface ReviewerFields {
  conversationLevel: string;
  authors: string[];
  authorDraft: string;
}

const ROLE_OPTIONS: { value: ProviderRole; label: string }[] = [
  { value: 'REVIEWER', label: 'Reviewer' },
  { value: 'FACTORY', label: 'Factory' },
];
```

Replace the three states `conversationLevel`, `authors`, `authorDraft` with one, and add `role`:

```tsx
  const [role, setRole] = useState<ProviderRole>(initial?.role ?? 'REVIEWER');
  const [reviewer, setReviewer] = useState<ReviewerFields>({
    conversationLevel: initial?.conversationLevel ?? '',
    authors: initial?.authors ?? [],
    authorDraft: '',
  });
  const patchReviewer = (patch: Partial<ReviewerFields>) => setReviewer((prev) => ({ ...prev, ...patch }));
```

`addAuthor` / `removeAuthor`:

```tsx
  function addAuthor() {
    const v = reviewer.authorDraft.trim();
    if (!v || reviewer.authors.includes(v)) {
      patchReviewer({ authorDraft: '' });
      return;
    }
    patchReviewer({ authors: [...reviewer.authors, v], authorDraft: '' });
  }

  function removeAuthor(a: string) {
    patchReviewer({ authors: reviewer.authors.filter((x) => x !== a) });
  }
```

In `submit`, the flush and the payload:

```tsx
    // Flush a typed-but-not-yet-added author so it isn't silently dropped on submit.
    const draft = reviewer.authorDraft.trim();
    const finalAuthors = draft && !reviewer.authors.includes(draft) ? [...reviewer.authors, draft] : reviewer.authors;

    const input: ProviderInput = {
      name: name.trim(),
      type,
      baseUrl: baseUrl.trim(),
      workspace: workspace.trim(),
      authKind,
      authUsername: authKind === 'basic' ? authUsername.trim() : null,
      botAccountId: botAccountId.trim(),
      enabled,
      authors: role === 'REVIEWER' ? finalAuthors : [],
      conversationLevel: role === 'REVIEWER' && reviewer.conversationLevel ? reviewer.conversationLevel : undefined,
      // Fixed at registration: on edit the stored role goes back as it came. A different one is a 409.
      role: editing && initial ? initial.role : role,
    };
```

Dialog title: `<h3>{editing ? 'Edit account' : 'Add account'}</h3>`. Submit button: `{busy ? 'Saving…' : editing ? 'Save changes' : 'Add account'}`. In `DeleteConfirmModal`: `<h3>Delete account</h3>`.

The Role field, as the **first** field in the form (before Name):

```tsx
          <label className="field">
            <span>Role</span>
            {editing && initial ? (
              <>
                <div className="field-static">{roleLabel(initial.role)}</div>
                <small className="field-hint">
                  Set at registration. To change it, register a new account with the other role and delete this one.
                </small>
              </>
            ) : (
              <>
                <Select
                  ariaLabel="Role"
                  value={role}
                  options={ROLE_OPTIONS}
                  onChange={(v) => setRole(v as ProviderRole)}
                />
                <small className="field-hint">
                  Reviewer reads pull requests and posts comments. Factory pushes branches and opens pull
                  requests as a separate machine account (ADR-038) — never as the reviewer.
                </small>
              </>
            )}
          </label>
```

Wrap the Conversation level field in `{role === 'REVIEWER' && ( … )}`, reading `reviewer.conversationLevel` and writing `patchReviewer({ conversationLevel: v })`.

The bot-account hint gains, after its existing text:

```tsx
              {role === 'FACTORY' && (
                <>
                  {' '}
                  A Factory account must resolve to a login: a push is authenticated as one, and a workspace
                  access token with no user cannot push.
                </>
              )}
```

The allowlist block, wrapped in `{role === 'REVIEWER' && ( … )}`:

```tsx
            <div className="field">
              <span>May command this bot</span>
              <div className="chip-add">
                <input
                  className="mono"
                  placeholder="stable user id"
                  value={reviewer.authorDraft}
                  onChange={(e) => patchReviewer({ authorDraft: e.target.value })}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      addAuthor();
                    }
                  }}
                />
                <button type="button" className="btn-ghost" onClick={addAuthor}>
                  Add
                </button>
              </div>
              <small className="field-hint">
                The id the forge reports for the user, not the handle: /fix accepts ids only, because a
                handle can change hands and /fix pushes code. Empty means everyone may /review; nobody may /fix.
              </small>
              {reviewer.authors.length > 0 && ( …chips as before, over reviewer.authors… )}
            </div>
```

- [ ] **Step 4: Run the form tests, the suite and the type check**

Run: `cd spire-ui && npx vitest run src/components/SettingsProviders.form.test.tsx && npm test && npx tsc --noEmit`
Expected: PASS, PASS, silent. Count the `useState` calls in `ProviderFormModal`: `grep -c "useState(" src/components/ProviderFormModal.tsx` → expect 13 for the form (name, type, baseUrl, workspace, authKind, authUsername, secret, botAccountId, enabled, busy, error, role, reviewer) plus 2 in `DeleteConfirmModal`.

- [ ] **Step 5: Record the remaining overage**

Create `techdebt/spire-ui/4-3-the-account-form-holds-thirteen-state-hooks.md`:

```markdown
# The account form holds thirteen state hooks against a cap of eight

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-ui/src/components/ProviderFormModal.tsx` (`ProviderFormModal`) |
| Found during | Accounts and roles (spec 2026-09-07), Task 9 |
| Date | 2026-09-07 |

## Issue

`~/.claude/rules/clean-code-react.md` caps a component at eight `useState` calls. The form had
fourteen before this work; it has thirteen after — the three reviewer-only fields were folded into
one `ReviewerFields` object and a `role` state was added. Recorded because the overage predates the
branch and no entry covered it, and because the direction of travel is the fix: group the remaining
scalar fields (`name`, `type`, `baseUrl`, `workspace`, `authKind`, `authUsername`, `secret`,
`botAccountId`, `enabled`) into one form-state object with a `patch` setter, the way `reviewer` now is.

## Risks

- Readability only. Every field is set from exactly one control and read in exactly one place.

## Suggested Solutions

- One `useState<AccountFields>` for the nine scalars plus `patch`, leaving `role`, `reviewer`, `busy`
  and `error` — four hooks. A mechanical change; the form tests cover every submitted field.
```

- [ ] **Step 6: Commit**

```bash
git add spire-ui/src/components/ProviderFormModal.tsx spire-ui/src/components/SettingsProviders.form.test.tsx \
        techdebt/spire-ui/4-3-the-account-form-holds-thirteen-state-hooks.md
git commit -m "Let the account form set a role, fixed after registration

The form gains a Role field: Reviewer by default, Factory for the
machine account the M2 factory needs and could not be registered from
the UI. On edit the stored role is shown read-only and sent back
unchanged. A Factory account has no allowlist and no conversation
level, so those fields are not offered for it. The allowlist asks for
the stable user id: /fix accepts ids only, and the field said username."
```

---

### Task 10: The Repositories screen shows who reviews and who pushes

**Files:**
- Create: `spire-ui/src/hooks/useServingAccounts.ts`
- Create: `spire-ui/src/components/ServingCell.tsx`
- Modify: `spire-ui/src/components/SettingsWebhookRepos.tsx` (title, note, empty state, picker label/filter/hint, two columns)
- Modify: `spire-ui/src/index.css`
- Test: `spire-ui/src/hooks/useServingAccounts.test.tsx` (create)
- Test: `spire-ui/src/components/SettingsWebhookRepos.serving.test.tsx` (create)
- Test: `spire-ui/src/components/SettingsWebhookRepos.form.test.tsx` (label/regex updates)

**Interfaces:**
- Consumes: `fetchServingAccounts`, `ServingAccounts`, `WebhookRepoView`, `verifyRepo`, `checkProvider` (`../api`); `servingChip` (Task 4).
- Produces: `ownerOf(w: Pick<WebhookRepoView, 'scope' | 'target'>): string`; `servingKey(type: string, owner: string): string`; `interface ServingLookup { data?: ServingAccounts; error?: string }`; `useServingAccounts(repos: WebhookRepoView[]): Record<string, ServingLookup>` (one request per distinct key); `ServingCell({ role: 'reviewer' | 'factory'; lookup: ServingLookup | undefined; repo: WebhookRepoView })`.

- [ ] **Step 1: Write the failing hook test**

`useServingAccounts.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import * as api from '../api';
import { ownerOf, servingKey, useServingAccounts } from './useServingAccounts';

const repo = (over: Partial<api.WebhookRepoView>): api.WebhookRepoView => ({
  id: 'TEST-w1',
  providerType: 'github',
  scope: 'repo',
  target: 'TEST-acme/widgets',
  webhookKey: 'TEST-key',
  hasSecret: true,
  enabled: true,
  createdAt: '2026-09-07T00:00:00Z',
  ...over,
});

const serving = (workspace: string): api.ServingAccounts => ({
  type: 'github',
  workspace,
  reviewer: { state: 'ok', id: 'TEST-r', name: 'TEST reviewer', botUsername: 'r', botAccountId: 'TEST-a' },
  factory: { state: 'missing', id: null, name: null, botUsername: null, botAccountId: null },
});

function Probe({ repos }: { repos: api.WebhookRepoView[] }) {
  const lookups = useServingAccounts(repos);
  return <pre data-testid="lookups">{JSON.stringify(lookups)}</pre>;
}

describe('ownerOf', () => {
  it('is the owner segment for a repository and the whole target for an organization', () => {
    expect(ownerOf({ scope: 'repo', target: 'TEST-acme/widgets' })).toBe('TEST-acme');
    expect(ownerOf({ scope: 'org', target: 'TEST-acme' })).toBe('TEST-acme');
  });
});

describe('useServingAccounts', () => {
  beforeEach(() => vi.restoreAllMocks());

  /** Two rows in one workspace ask once: the answer is per (forge, workspace), not per repository. */
  it('asks once per distinct forge and owner', async () => {
    const fetch = vi.spyOn(api, 'fetchServingAccounts').mockImplementation(async (_t, ws) => serving(ws));
    const repos = [
      repo({ id: 'TEST-w1', target: 'TEST-acme/widgets' }),
      repo({ id: 'TEST-w2', target: 'TEST-acme/gadgets' }),
      repo({ id: 'TEST-w3', scope: 'org', target: 'TEST-other' }),
    ];
    render(<Probe repos={repos} />);

    await waitFor(() => expect(JSON.parse(screen.getByTestId('lookups').textContent ?? '{}')).toHaveProperty([servingKey('github', 'TEST-other')]));
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(fetch).toHaveBeenCalledWith('github', 'TEST-acme');
    expect(fetch).toHaveBeenCalledWith('github', 'TEST-other');
    const lookups = JSON.parse(screen.getByTestId('lookups').textContent ?? '{}');
    expect(lookups[servingKey('github', 'TEST-acme')].data.reviewer.state).toBe('ok');
  });

  it('keeps a failed lookup as an error, not as an empty answer', async () => {
    vi.spyOn(api, 'fetchServingAccounts').mockRejectedValue(new Error('Failed to load the accounts serving this workspace'));
    render(<Probe repos={[repo({})]} />);

    await waitFor(() => {
      const lookups = JSON.parse(screen.getByTestId('lookups').textContent ?? '{}');
      expect(lookups[servingKey('github', 'TEST-acme')].error).toMatch(/failed to load/i);
    });
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd spire-ui && npx vitest run src/hooks/useServingAccounts.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Create `useServingAccounts.ts`**

```ts
import { useEffect, useState } from 'react';
import { fetchServingAccounts, type ServingAccounts, type WebhookRepoView } from '../api';

export interface ServingLookup {
  data?: ServingAccounts;
  error?: string;
}

/** The workspace a registration belongs to — the owner segment, or the whole target for an org row. */
export function ownerOf(w: Pick<WebhookRepoView, 'scope' | 'target'>): string {
  return w.scope === 'org' ? w.target : w.target.split('/')[0];
}

/** One key per (forge, owner). Neither can contain a slash: the owner is the segment before one. */
export function servingKey(type: string, owner: string): string {
  return `${type}/${owner}`;
}

/**
 * Which accounts serve each registration's forge and workspace, asked of the orchestrator once per
 * distinct pair rather than once per row: the answer is keyed on (forge, workspace), and a page of
 * twenty repositories in one organization is one request. A failed request is kept as an error so
 * the cell can say "unknown" — never "none", which is the answer that would send an operator to
 * register an account they already have.
 */
export function useServingAccounts(repos: WebhookRepoView[]): Record<string, ServingLookup> {
  const [lookups, setLookups] = useState<Record<string, ServingLookup>>({});

  useEffect(() => {
    let alive = true;
    const pairs = new Map<string, { type: string; owner: string }>();
    for (const w of repos) {
      const owner = ownerOf(w);
      pairs.set(servingKey(w.providerType, owner), { type: w.providerType, owner });
    }
    setLookups({});
    for (const [key, { type, owner }] of pairs) {
      fetchServingAccounts(type, owner)
        .then((data) => {
          if (alive) setLookups((prev) => ({ ...prev, [key]: { data } }));
        })
        .catch((err: unknown) => {
          if (alive) setLookups((prev) => ({ ...prev, [key]: { error: err instanceof Error ? err.message : String(err) } }));
        });
    }
    return () => {
      alive = false;
    };
  }, [repos]);

  return lookups;
}
```

- [ ] **Step 4: Run the hook test**

Run: `cd spire-ui && npx vitest run src/hooks/useServingAccounts.test.tsx`
Expected: PASS.

- [ ] **Step 5: Write the failing screen tests**

`SettingsWebhookRepos.serving.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsWebhookRepos from './SettingsWebhookRepos';
import * as api from '../api';

const renderPage = () => render(<MemoryRouter><SettingsWebhookRepos /></MemoryRouter>);

const repo = (over: Partial<api.WebhookRepoView>): api.WebhookRepoView => ({
  id: 'TEST-w1',
  providerType: 'github',
  scope: 'repo',
  target: 'TEST-acme/widgets',
  webhookKey: 'TEST-key',
  hasSecret: true,
  enabled: true,
  createdAt: '2026-09-07T00:00:00Z',
  ...over,
});

const account = (state: api.ServingState, name: string): api.ServingAccount => ({
  state,
  id: state === 'missing' ? null : `TEST-${name}`,
  name: state === 'missing' ? null : name,
  botUsername: null,
  botAccountId: null,
});

const serving = (reviewer: api.ServingAccount, factory: api.ServingAccount): api.ServingAccounts => ({
  type: 'github',
  workspace: 'TEST-acme',
  reviewer,
  factory,
});

const rowFor = async (target: string) => (await screen.findByText(target)).closest('tr') as HTMLElement;

describe('SettingsWebhookRepos — who reviews and who pushes', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
  });

  it('is titled Repositories and shows both roles for a row, green only when usable', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('no-login', 'factory-bot')),
    );
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Repositories' })).toBeInTheDocument();
    const row = await rowFor('TEST-acme/widgets');
    const reviewer = await within(row).findByText('reviewer-bot');
    const factory = within(row).getByText('factory-bot');
    expect(reviewer.closest('.pill')).toHaveClass('completed');
    expect(factory.closest('.pill')).toHaveClass('refused');
  });

  it('says none when no account serves a role, in grey', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('missing', '')),
    );
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    const none = await within(row).findByText('none');
    expect(none.closest('.pill')).toHaveClass('cancelled');
    expect(within(row).queryByRole('button', { name: /verify push account/i })).not.toBeInTheDocument();
  });

  it('renders a state it does not know as unknown, never green', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving({ ...account('ok', 'reviewer-bot'), state: 'brand-new' as api.ServingState }, account('missing', '')),
    );
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    const chip = await within(row).findByText('unknown (brand-new)');
    expect(chip.closest('.pill')).toHaveClass('cancelled');
  });

  it('renders a failed lookup as unknown rather than as none', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockRejectedValue(new Error('Failed to load the accounts serving this workspace'));
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    expect(await within(row).findAllByText('unknown')).toHaveLength(2);
    expect(within(row).queryByText('none')).not.toBeInTheDocument();
  });

  /** The chip's own account is what gets verified — the factory's Verify must not probe with the reviewer's token. */
  it('verifies each role with that role’s account, and says push rights are not checked', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({})]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('ok', 'factory-bot')),
    );
    const verify = vi.spyOn(api, 'verifyRepo').mockResolvedValue({ ok: true, detail: null });
    renderPage();

    const row = await rowFor('TEST-acme/widgets');
    fireEvent.click(await within(row).findByRole('button', { name: /verify push account for TEST-acme\/widgets/i }));
    await waitFor(() => expect(verify).toHaveBeenCalledWith('TEST-factory-bot', 'TEST-acme/widgets'));
    expect(await within(row).findByText(/push rights are not checked/i)).toBeInTheDocument();

    fireEvent.click(within(row).getByRole('button', { name: /verify review account for TEST-acme\/widgets/i }));
    await waitFor(() => expect(verify).toHaveBeenCalledWith('TEST-reviewer-bot', 'TEST-acme/widgets'));
  });

  /** An organization row has no repository to GET; its verify is the account's own connectivity check. */
  it('checks the account itself for an organization row', async () => {
    vi.spyOn(api, 'fetchWebhookRepos').mockResolvedValue([repo({ scope: 'org', target: 'TEST-acme' })]);
    vi.spyOn(api, 'fetchServingAccounts').mockResolvedValue(
      serving(account('ok', 'reviewer-bot'), account('missing', '')),
    );
    const check = vi.spyOn(api, 'checkProvider').mockResolvedValue({ ok: true, account: 'reviewer-bot', detail: null });
    renderPage();

    const row = await rowFor('TEST-acme');
    fireEvent.click(await within(row).findByRole('button', { name: /verify review account for TEST-acme$/i }));
    await waitFor(() => expect(check).toHaveBeenCalledWith('TEST-reviewer-bot'));
  });
});
```

In `SettingsWebhookRepos.form.test.tsx`: the `provider()` fixture already has `role: 'REVIEWER'` from Task 4; change the combobox query `/provider/i` → `/workspace/i`; the option regexes to `/github · TEST?-?acme \(Acme Bot\)/` → concretely `/github · acme \(Acme Bot\)/` and `/gitlab · my-team \(Lab Bot\)/`; the empty-state regex to `/register a reviewer account first/i`. Add one test:

```tsx
  /** The picker registers what will be REVIEWED; a Factory account has nothing to review with. */
  it('offers reviewer accounts only', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([
      provider({ id: 'p1', name: 'Acme Bot', type: 'github', workspace: 'acme' }),
      provider({ id: 'p9', name: 'Acme Factory', type: 'github', workspace: 'acme', role: 'FACTORY' }),
    ]);
    renderPage();
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    fireEvent.click(await screen.findByRole('combobox', { name: /workspace/i }));
    await waitFor(() => expect(screen.getByRole('option', { name: /github · acme \(Acme Bot\)/ })).toBeInTheDocument());
    expect(screen.queryByRole('option', { name: /Acme Factory/ })).not.toBeInTheDocument();
  });
```

(The form-picker tests mock `fetchWebhookRepos` to `[]`, so they never trigger a serving lookup; no `fetchServingAccounts` mock is needed there.)

- [ ] **Step 6: Run both screen test files to verify they fail**

Run: `cd spire-ui && npx vitest run src/components/SettingsWebhookRepos.serving.test.tsx src/components/SettingsWebhookRepos.form.test.tsx`
Expected: FAIL — heading is "Webhooks", no chips, the combobox is still named Provider.

- [ ] **Step 7: Create `ServingCell.tsx`**

```tsx
import { useState } from 'react';
import { checkProvider, verifyRepo, type WebhookRepoView } from '../api';
import type { ServingLookup } from '../hooks/useServingAccounts';
import { servingChip } from './servingAccounts';

type Role = 'reviewer' | 'factory';

interface Props {
  role: Role;
  lookup: ServingLookup | undefined;
  repo: WebhookRepoView;
}

/**
 * One role's account for one repository row, as a chip, with a Verify that probes with THAT
 * account's token. A repository row GETs the repository; an organization row has nothing to GET and
 * runs the account's own connectivity check instead.
 *
 * <p>For the factory the verify is a read: it proves the token can see the repository and nothing
 * about pushing. The label says so, because "verified" beside a push identity would be read as
 * "can push", and a false yes there costs an operator a failed run to discover.
 */
export default function ServingCell({ role, lookup, repo }: Props) {
  const account = lookup?.data?.[role];
  const chip = servingChip(account, lookup?.error ?? null);
  const [verify, setVerify] = useState<{ state: 'idle' | 'checking' | 'ok' | 'fail'; detail?: string }>({
    state: 'idle',
  });
  const canVerify = account !== undefined && account.state !== 'missing' && account.id !== null;

  async function onVerify() {
    if (account === undefined || account.id === null) return;
    setVerify({ state: 'checking' });
    try {
      const result = repo.scope === 'repo' ? await verifyRepo(account.id, repo.target) : await checkProvider(account.id);
      setVerify(result.ok ? { state: 'ok' } : { state: 'fail', detail: result.detail ?? 'Not reachable' });
    } catch (err) {
      setVerify({ state: 'fail', detail: err instanceof Error ? err.message : String(err) });
    }
  }

  const okText = role === 'factory' ? 'reachable (read). Push rights are not checked.' : 'reachable';
  const verb = role === 'factory' ? 'push' : 'review';

  return (
    <div className="serving-cell">
      <span className={`pill ${chip.pill}`} title={chip.title}>
        <span className="glyph"></span>
        {chip.label}
      </span>
      {canVerify && (
        <button
          type="button"
          className="btn-ghost serving-verify"
          onClick={() => void onVerify()}
          disabled={verify.state === 'checking'}
          aria-label={`Verify ${verb} account for ${repo.target}`}
        >
          {verify.state === 'checking' ? 'Verifying…' : 'Verify'}
        </button>
      )}
      {verify.state === 'ok' && <div className="wh-verify ok">{okText}</div>}
      {verify.state === 'fail' && <div className="wh-verify fail">{verify.detail}</div>}
    </div>
  );
}
```

CSS, after the `.tab.active` rule added in Task 6:

```css
  /* Settings → Repositories: the two who-serves-this-row cells. */
  .serving-cell { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
  .serving-verify { padding: 2px 8px; font-size: 11.5px; }
```

- [ ] **Step 8: Edit `SettingsWebhookRepos.tsx`**

Imports: add `ServingCell from './ServingCell'` and `{ ownerOf, servingKey, useServingAccounts } from '../hooks/useServingAccounts'`.

In the page component, after `const [confirmDelete, …]`: `const serving = useServingAccounts(repos);`.

Title: `<h2 className="prov-title">Repositories</h2>`. The note (lines 79-84): its last sentence becomes `The owner must match a reviewer account registered under Settings → Accounts.`

Table head:

```tsx
                <th>Scope</th>
                <th>Target</th>
                <th>Forge</th>
                <th>Reviewed by</th>
                <th>Pushed by</th>
                <th>Payload URL (path)</th>
                <th>Secret</th>
                <th>Enabled</th>
                <th></th>
```

Body: after the `{w.providerType}` cell, add

```tsx
                  <td>
                    <ServingCell role="reviewer" lookup={serving[servingKey(w.providerType, ownerOf(w))]} repo={w} />
                  </td>
                  <td>
                    <ServingCell role="factory" lookup={serving[servingKey(w.providerType, ownerOf(w))]} repo={w} />
                  </td>
```

In `useWebhookProviders`, the filter becomes `const usable = all.filter((p) => p.enabled && p.role === 'REVIEWER');` and the `owner` line uses `ownerOf(initial)`. Add to its JSDoc: *"Reviewer accounts only: this form registers what will be reviewed, and a Factory account has nothing to review with."*

In the modal: the empty-state text becomes `Register a reviewer account first under Settings → Accounts, then add a webhook for one of its repositories.` The picker label `<span>Provider</span>` → `<span>Workspace</span>`; `ariaLabel="Provider"` → `ariaLabel="Workspace"`; option label → `` `${p.type} · ${p.workspace} (${p.name})` ``; under the `Select`, add

```tsx
                  <small className="field-hint">
                    Which accounts review and push here is decided by the forge and workspace, not stored on this row.
                  </small>
```

- [ ] **Step 9: Run the tests, the contract test, the suite and the type check**

Run: `cd spire-ui && npx vitest run src/components/SettingsWebhookRepos.serving.test.tsx src/components/SettingsWebhookRepos.form.test.tsx src/styles.contract.test.ts && npm test && npx tsc --noEmit`
Expected: PASS, PASS, silent. `wc -l src/components/SettingsWebhookRepos.tsx` must not exceed its Task-start count by more than ~25 lines.

- [ ] **Step 10: Commit**

```bash
git add spire-ui/src/hooks/useServingAccounts.ts spire-ui/src/hooks/useServingAccounts.test.tsx \
        spire-ui/src/components/ServingCell.tsx spire-ui/src/components/SettingsWebhookRepos.tsx \
        spire-ui/src/components/SettingsWebhookRepos.serving.test.tsx spire-ui/src/components/SettingsWebhookRepos.form.test.tsx \
        spire-ui/src/index.css
git commit -m "Show which account reviews and which pushes on each repository row

The Repositories screen asks the orchestrator which accounts serve each
row's forge and workspace, once per distinct pair, and renders both
roles as chips: green only when usable, amber for a registration that
exists but will not work, grey for disabled, missing or unknown. Each
chip verifies with its own account. The picker offers reviewer accounts
only and says that nothing about accounts is stored on the row."
```

---

### Task 11: The Context screen says who uses each source

**Files:**
- Modify: `spire-ui/src/components/SettingsContextProviders.tsx:230-260`
- Test: `spire-ui/src/components/SettingsContextProviders.usedby.test.tsx` (create)

- [ ] **Step 1: Write the failing test**

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsContextProviders from './SettingsContextProviders';
import * as api from '../api';

const source: api.ContextProviderView = {
  id: 'TEST-c1',
  name: 'TEST Jira',
  type: 'jira',
  baseUrl: 'https://test-acme.atlassian.net',
  authKind: 'basic',
  username: 'jira-bot@example.invalid',
  projectKeys: null,
  hasSecret: true,
  enabled: true,
  isDefault: false,
  createdAt: '2026-09-07T00:00:00Z',
  lastCheckAt: null,
  lastCheckOk: null,
  lastCheckError: null,
};

/**
 * Every context source is read by exactly one consumer today, the reviewer's context aggregator.
 * The column says so, and becomes data when M3 registers a write-capable account on the same host.
 */
describe('SettingsContextProviders — Used by', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([source, { ...source, id: 'TEST-c2', name: 'TEST Wiki', type: 'confluence' }]);
    vi.spyOn(api, 'checkContextProvider').mockResolvedValue({ ok: true, account: 'jira-bot', detail: null } as never);
  });

  it('shows Reviewer · read on every row', async () => {
    render(<MemoryRouter><SettingsContextProviders /></MemoryRouter>);
    expect(await screen.findAllByText('Reviewer · read')).toHaveLength(2);
    expect(screen.getByRole('columnheader', { name: 'Used by' })).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd spire-ui && npx vitest run src/components/SettingsContextProviders.usedby.test.tsx`
Expected: FAIL — no such text.

- [ ] **Step 3: Add the column**

In the `<thead>`, after `<th>Type</th>`: `<th>Used by</th>`. In the row, after `<td className="mono">{p.type}</td>`:

```tsx
                  {/* One consumer today: the review worker's context aggregator, reading. Becomes data
                      when M3 registers a write-capable account against the same host (ADR-035). */}
                  <td>
                    <span className="prov-sub">Reviewer · read</span>
                  </td>
```

- [ ] **Step 4: Run the test and the suite**

Run: `cd spire-ui && npx vitest run src/components/SettingsContextProviders.usedby.test.tsx && npm test && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add spire-ui/src/components/SettingsContextProviders.tsx spire-ui/src/components/SettingsContextProviders.usedby.test.tsx
git commit -m "Say who uses each context source

Every source is read by one consumer today, the reviewer. The column
makes that visible beside the account that reads it."
```

---

### Task 12: Documentation

**Files:**
- Modify: `README.md:80-81`
- Modify: `docs/ROADMAP.md:53`
- Modify: `docs/SMOKE-TEST.md` lines 63, 87, 198, 265, 608, 808, 1589, 1615, 1644-1652
- Modify: `docs/HISTORY.md` (append one bullet at the end)
- Check: `CLAUDE.md` — `grep -n "Settings" CLAUDE.md`; update any mention of the three old screen names (there may be none).

- [ ] **Step 1: Make the edits**

`README.md:80-81`:

```
Register an SCM account in the UI (Settings -> Accounts, role Reviewer) and an LLM in Settings -> LLM — both
encrypted at rest. For GitHub/GitLab, add a per-repo webhook in Settings -> Repositories and point the
```

`docs/ROADMAP.md:53`: `- **Provider registry** (Settings → Accounts): encrypted credentials in the DB, no `.env` tokens.`

`docs/SMOKE-TEST.md`:
- 63: `- The **PR-author allowlist** is per reviewer account (Settings → Accounts → May command this bot), so`
- 87: `   Settings → Accounts, leave "Bot account id" blank, and it is resolved from the`
- 198: `1. Register a **GitHub account** in Settings → Accounts (role Reviewer; workspace = repo owner, e.g.`
- 265: `1. Register a **GitLab account** in Settings → Accounts (role Reviewer):`
- 608: `2. **Rejected credential.** Settings → Accounts, edit an account's token to a wrong value and`
- 808: `… — check Settings → Accounts |`
- 1589: `| 5 | Open Settings → Accounts → People as an **admin** | …` (rest of the row unchanged)
- 1615: `Unlink under Settings → Accounts → People, and remove the application with the trash control beside it (or`
- 1644-1652: replace the paragraph and the `curl` block with:

```
4. **The machine account.** A *separate* forge account with write access to that repository and a
   token that can push (ADR-038: the factory never pushes as the review bot). Register it under
   Settings → Accounts → Add account with **Role: Factory**; the login is resolved from the token
   on save. The row's Identity column must show `@<login>` — a Factory account with no resolved
   login cannot push, and Settings → Repositories shows it amber as "no login". The same thing
   through the API:

   ```bash
   curl -sS -X POST http://localhost:34080/api/providers -H 'content-type: application/json' -d '{
     "name":"factory-bot","type":"github","baseUrl":"https://api.github.com","workspace":"<owner>",
     "authKind":"bearer","secret":"<machine-account token>","enabled":true,"authors":[],
     "botUsername":"<machine-account login>","role":"FACTORY"}'
   ```
```

`docs/HISTORY.md` — append as the last bullet:

```
- **Accounts and roles (2026-09-07, PR #120) — the screens say what the code does.** The Settings
  screen labelled *Repositories* was the SCM account registry, and the one labelled *Webhooks* was
  the screen that lists repositories; `POST /api/runs` sent operators to "Settings -> Providers",
  which no nav had, to set a role the form could not set. Renamed: **Accounts** (tabs *Machine
  accounts* and *People*; the Operators screen became the People tab) and **Repositories**; old
  routes redirect and keep `?edit=`. The account form gained a **Role** field that is fixed after
  registration — a `PUT` that changes it is refused with 409, because a role-less `PUT` once demoted
  a Factory row to reviewer and handed the review path the push token. `GET /api/providers/serving`
  answers which accounts serve a forge + workspace in five states, using the pipeline's own
  resolvers, and the Repositories screen shows both roles per row with a per-account Verify.
  Tracker accounts are listed read-only on Accounts; Context shows *Used by*. The allowlist field
  asks for the stable user id, which is what `/fix` accepts. No new tables. Design:
  `docs/superpowers/specs/2026-09-07-accounts-and-roles-design.md`; out of scope, with reasons, in its §11.
```

- [ ] **Step 2: Check nothing still names the old screens**

Run: `grep -rnE "Settings (->|→) (Providers|Webhooks|Operators)\b" README.md docs/*.md CLAUDE.md | grep -v "docs/superpowers/"`
Expected: no output. (Historical specs and plans under `docs/superpowers/` keep their wording.)

- [ ] **Step 3: Commit**

```bash
git add README.md docs/ROADMAP.md docs/SMOKE-TEST.md docs/HISTORY.md CLAUDE.md
git commit -m "Document the Accounts and Repositories screens

The runbook, the README and the roadmap named screens the nav no longer
has. The smoke test's factory step registers the machine account from
the UI now that the form has a Role field."
```

(Drop `CLAUDE.md` from the `git add` if it needed no change.)

---

### Task 13: Mutation checks — each guard has exactly one test that kills it

No code is committed by this task unless a check survives, in which case the weak test is fixed and committed.

**Method.** Copy the file to the session scratchpad first and restore from THAT copy — never with `git checkout`, which reverts to HEAD and would delete the branch's own work. Match `\r?\n` in any multi-line edit. After applying a mutation, `grep` for the text that must now be gone; if the file did not change, the check is INVALID, not SURVIVED. A mutant that does not compile is INVALID too.

- [ ] **Check 1 — the create path stops writing the role.** In `ProviderRegistry.create`, change `ps.setString(13, ProviderRole.of(in.role()).name());` to `ps.setString(13, ProviderRole.REVIEWER.name());`. Run `./gradlew :spire-orchestrator:test --tests 'dev.codespire.orchestrator.provider.ProviderResourceTest' --tests 'dev.codespire.orchestrator.provider.ProviderServingResourceTest' --rerun`. Expected to FAIL: `aFactoryRoleSurvivesTheRestPathOnCreateAndUpdate` (role echoed as REVIEWER) and the serving tests that register a FACTORY row (`aFactoryWithALoginIsOk`, `aFactoryWithNoLoginCannotPush`, `aDisabledRowIsDisabledNotMissing` — the factory half). Restore.

- [ ] **Check 2 — the factory chip lies about pushing.** In `ProviderResource.serving`, swap the factory branch to `canPush(type, workspace) ? ServingAccount.of("no-login", v) : ServingAccount.of("ok", v)`. Run the serving test class. Expected to FAIL: `aFactoryWithNoLoginCannotPush` and `aFactoryWithALoginIsOk`, no others. Restore.

- [ ] **Check 3 — the role guard is deleted.** In `ProviderRegistry.update`, delete the `if (in.role() != null && …) { throw new RoleIsFixedAtRegistration(…); }` block. Run `ProviderRegistryTest` and `ProviderResourceTest`. Expected to FAIL: `aRoleIsFixedAtRegistration` and `aFactoryRoleSurvivesTheRestPathOnCreateAndUpdate` (200 where 409 was expected). Restore.

- [ ] **Check 4 — the chip reader defaults to green.** In `servingAccounts.ts`, change the unknown-state branch's `pill: 'cancelled'` to `pill: 'completed'`. Run `cd spire-ui && npx vitest run src/components/servingAccounts.test.ts src/components/SettingsWebhookRepos.serving.test.tsx`. Expected to FAIL: `renders an unlisted state as unknown, never as ok` and `renders a state it does not know as unknown, never green`. Restore.

- [ ] **Check 5 — the redirect drops the query.** In `RedirectKeepingQuery.tsx`, change `to={{ pathname: to, search }}` to `to={to}`. Run `npx vitest run src/App.routes.test.tsx`. Expected to FAIL: the two redirect cases that carry `?edit=`. Restore.

- [ ] **Record the outcome** in the PR checklist ("Mutation checks: 5/5 killed" or the fix made). Confirm the working tree is clean: `git status --porcelain` prints nothing.

---

## After the tasks

1. Run `/code-review --service=global --feature=accounts-and-roles` (the four-lens review). Fix findings; record dispositions in `.claude/reviews/global/accounts-and-roles.md`.
2. Final verification, one Gradle invocation at a time: `./gradlew testFast --rerun-tasks`; `./gradlew :spire-orchestrator:test --rerun`; `./gradlew :spire-gateway:test --rerun`; `cd spire-ui && npm test && npx tsc --noEmit`.
3. Rewrite the `CLAUDE.md` Status measured-counts line with the new totals and date.
4. Mark PR #120 ready for review.
