# Carefully-worded rejection messages are thrown away before the client sees them

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Medium |
| Location | 57 `new BadRequestException(String)` / `new ClientErrorException(String, Status)` sites across 14 files in `spire-orchestrator/src/main/java` |
| Found during | ADR-023 LLM cost accounting, Task 5 (REST validation) |
| Date | 2026-08-07 |

## Issue

`throw new BadRequestException("maxTokens must be a positive integer")` sets the **exception's** message.
It does not set the HTTP response body. The client receives `400` with an empty entity, and the sentence
explaining what to fix is discarded.

Found empirically, not theoretically: Task 5 wrote the brief's exact `throw new BadRequestException(...)`
code and three tests failed on body assertions. The fix was to route every throw through a helper that
builds the response explicitly:

```java
    private static BadRequestException badRequest(String message) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }
```

`PromptResource` already had that pattern; `LlmModelResource` now does too. The remaining twelve files do
not:

`ContextKeyValidator`, `ContextProviderResource`, `DlqResource`, `ManualRegisterResource`,
`LlmKeyValidator`, `LlmProviderResource`, `ProviderResource`, `PublicHttpsGuard`,
`ConversationLevelResource`, `ConversationSettingsResource`, `ReviewModeResource`,
`ReviewSettingsResource`.

(Several appear in both camps — a file may build a body on one path and throw bodiless on another, which
is worse than being uniformly wrong because it makes the behaviour look intentional.)

## Risks

This directly defeats one of the project's own stated conventions — *"error messages should be actionable:
say what went wrong AND what to do"*. Every one of these messages was written to satisfy that rule, and
none of them arrives.

The concrete cost is operator time on the settings screens, which are the only consumers. A rejected
provider or model save shows the browser's generic 400 handling, so the operator learns that something was
invalid but not which field or why. The messages are unusually good — the pricing rejection added by
ADR-023 tells an operator to mark a model `UNMETERED` if its inference is genuinely free, which is
precisely the guidance somebody needs at that moment and precisely what they do not currently get.

Nothing breaks, no data is at risk, and no request succeeds that should fail — the status codes are all
correct. That is why this is Medium rather than High, and also why it has survived unnoticed: the tests
that assert status codes pass, and only a test asserting the *body* catches it.

## Suggested Solutions

1. **Add the `badRequest`/`conflict` helper to each resource and route every throw through it** (the fix
   already applied in `LlmModelResource` and `PromptResource`). Mechanical, file-local, no shared module
   needed. Do it per file rather than as one sweeping commit, so each diff stays reviewable.
2. **Register an `ExceptionMapper<WebApplicationException>`** that copies the exception's message into the
   body when the response has no entity. One class fixes all 57 sites and any future one, at the cost of
   making the behaviour implicit — a reader of the `throw` still cannot tell whether the message is
   delivered. Prefer this only alongside a test that pins the mapping.
3. **Assert bodies, not just statuses, in resource tests.** Whichever fix lands, the reason this persisted
   is that every existing test checked `statusCode(400)` alone. Without body assertions the fix can regress
   silently, exactly as the original defect arrived.

## The same absence has a second symptom (added 2026-08-07, ADR-023)

There is **no `ExceptionMapper` of any kind in this module**, which also means a plain
`IllegalArgumentException` thrown by a service or registry becomes a **500**, not a 400 — it is not a
`WebApplicationException`, so nothing translates it.

ADR-023 made that concrete. `LlmProviderRegistry.create/update` now reject a provider naming an unpriceable
model with an `IllegalArgumentException`, and `LlmModelRegistry` rejects invalid pricing the same way. Those
never surface as 500s today **only because `LlmProviderResource.validate()` and `LlmModelResource.validate()`
perform byte-identical checks first** and throw a mapped `BadRequestException`.

That is a trap, not a design: the resource-layer checks *look* like pure duplication of the registry's, so
someone will eventually delete one as redundant and silently convert a clean 400 into a 500. A comment at
each site now says it owns the status code and must not be removed — but a comment is the weakest possible
enforcement, and the duplication remains the only thing holding the contract.

**The durable fix is the same `ExceptionMapper` work as Solution 2 above**, extended to cover
`IllegalArgumentException` (the priceable-model trap above). Doing both symptoms together is why they
share this entry: one mapper class fixes the missing bodies *and* removes the need for the duplicated
validation to exist for status-code reasons.

**The model-rename instance of this is now fixed** (commit `5e8c3cc`, followed by `cb5b8d5` giving the
in-use refusal its own `ModelInUseException` rather than a bare `IllegalStateException` shared with
every other registry failure). `LlmModelResource.update` now catches it and answers **409** with the
registry's message, mirroring `delete`. It is kept here as the worked example of the class rather than
deleted, because it is exactly how this entry's broader claim was found in the first place: a guard
that throws a plain runtime exception has no body at the client until something *specifically* catches
it, and until this fix, `update` was the one site on this branch where nothing did. The
`IllegalArgumentException` trap above is not fixed by this — it is a different exception type at a
different call site (`LlmProviderRegistry.requirePriceableModel`) and still relies on
`LlmProviderResource.validate()`'s duplicate check rather than its own catch.
