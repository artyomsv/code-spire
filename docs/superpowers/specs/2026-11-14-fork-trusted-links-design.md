# Fork reviews: context links from trusted people (PR 8b)

Step 8 (PR #419) cut all linked context from an untrusted pull request (a fork, or unknown provenance): the fork
author writes the title, branch and description the links are read from, and could make the review load private
issues and documents into a public review. Operator decisions, 2026-11-13 and 2026-11-14: take links again, but only
from text written by people the repository trusts.

## Decided with the operator

- On an untrusted pull request, links come only from:
  - comments on the pull request whose author has push access;
  - the description, title and branch, when the pull request's author has push access.
- "Push access" is the `/fix` rule: `FixAuthorization.decide` with the person's explicit ALLOW/DENY override on this
  repository, then the forge's effective permission. UNKNOWN does not count.
- The reviewer bot's own comments never count.
- Same-repository pull requests are unchanged: links from title, branch and description, as today.
- Applies to the API and the harness review paths alike (the one `GatherContext` decision in `ContextLinks`).
- Accepted limit: the first review usually starts before anyone comments, so a trusted comment helps the next
  re-review or a Re-run.

## Shape (builder's calls, recorded)

- The review worker reports where each link came from: for an untrusted pull request it also reads the pull request's
  comments and sends, per source, the author's stable provider id and the links found. Same-repository pull requests
  read no comments.
- The orchestrator decides trust in `ContextLinks` (one permission read per distinct author per review) and gathers the
  union of the trusted sources' links.
- The timeline records the decision: kept links with the authors that counted, and the authors that did not count and
  why (`ContextLinksTrusted` / the existing `ContextLinksSkipped`). No comment text is stored.
- A permission read that fails counts as not trusted, and the review continues.
- Bitbucket forks stay refused (step 8); GitHub and GitLab are in scope.
