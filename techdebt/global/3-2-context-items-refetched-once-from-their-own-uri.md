# Context items are fetched twice, because level 2 re-extracts their own `uri`

| Field | Value |
|-------|-------|
| Criticality | Medium |
| Complexity | Small |
| Location | `spire-review-worker/src/main/java/dev/codespire/worker/pipeline/ContextWorker.java:217`, `spire-context-github/.../GitHubIssueReferenceSource.java`, `spire-context-gitlab/.../GitLabIssueReferenceSource.java` |
| Found during | Whole-branch review of the GitHub/GitLab Issues context providers (2026-07-30) |
| Date | 2026-07-30 |

## Issue

`ContextWorker`'s bounded two-level collection mines the text retrieved at level 1 for new
references. `corpusOf` includes each item's `uri()` alongside its title and body:

```java
sb.append(item.title()).append('\n').append(item.body()).append('\n');
if (item.uri() != null) {
    sb.append(item.uri()).append('\n');
}
```

Both new providers set `uri` to the API's `html_url` / `web_url` — which is exactly the shape their
own URL patterns match. So every item resolved at level 1 is re-extracted at level 2 as a fresh
reference and fetched a second time.

`ContextReferenceSource.normalize` cannot suppress it: the new implementations normalize to
lowercase with a trailing slash stripped, so the URL form and the bare or qualified form a level-1
reference arrived as produce different values, and `seen` does not recognise the URL as already
requested.

`JiraContextProvider` never had this problem — `JiraTicketKeys` normalizes both the `PROJ-123` key
and the `/browse/PROJ-123` URL to the same value, so the round trip closes.

The output is unaffected: `ContextWorker` de-duplicates assembled items by `uri()`, so the second
fetch produces an item that is discarded.

## Risks

Wasted API calls inside a bounded budget, not wrong data.

The cost compounds: each re-fetch is an object call *plus* its comments/notes call, so a review
whose pull request references ten issues can spend forty sequential calls where twenty would do —
inside the aggregator's 20-second fan-out timeout, shared with every other configured provider.

The failure mode when it bites is a timeout, which surfaces as an `ERROR` contribution and a review
that proceeds with no context. To an operator that reads as "context stopped working" rather than
"context was too slow", so the cause is not obvious from the dashboard.

It also doubles the rate-limit consumption of a token that may be shared with the SCM adapter for
the same host.

## Suggested Solutions

1. **Normalize the URL form to the reference form in each `ContextReferenceSource.normalize`** —
   the approach `JiraTicketKeys` already takes. Parse the URL to a `Ref` and emit its canonical
   `owner/repo#number` (or `group/project#iid`) string, so a level-1 reference and the `uri` of the
   item it produced collapse to one value in `seen`. Contained to the two adapters, no change to
   `ContextWorker`, and it fixes the cause rather than the symptom.
2. **Stop putting `uri` in the level-2 corpus.** One line in `ContextWorker`, but it would also stop
   a genuinely useful case: a Jira ticket body that links a Confluence page is discovered through
   text, not through the item's own `uri`, so the loss is small — but it is a behaviour change for
   every provider, including the two that do not have this problem.
3. **De-duplicate by `uri` before fetching rather than after.** Would need the provider to know
   which URIs level 1 already produced, which the `ContextRequest` does not currently carry.

Option 1 is preferred: smallest blast radius, follows the precedent that already works, and leaves
the two-level design intact.
