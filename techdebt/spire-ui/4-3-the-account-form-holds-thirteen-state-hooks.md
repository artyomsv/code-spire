# The account form holds thirteen state hooks against a cap of eight

**Closed 2026-09-12 (#148).** Scalar fields are grouped in `AccountFields`; reviewer fields,
credential controls and deletion live in focused components. `ProviderFormModal` has five state
hooks and its component body is below 250 lines. Existing form behavior and Atlassian registration
are covered by the UI suite. The original diagnosis below is retained as history.

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

The same file also exceeds the 250-line component cap, and for the same reason. Task 7 extracted the
dialog out of the page as a pure move and it landed at **370 lines**; this task's Role field, its two
hints and the two reviewer-only wrappers took it to **429 lines**. Both overages have one remedy —
the state grouping above removes roughly nine declarations and their inline setters — so they are
recorded together rather than as two entries that would be closed by the same commit. At 429 lines
the file is 179 over the 250-line component cap, and over the 300-line general guideline as well.
Splitting the JSX into a `ReviewerFieldsSection` child is the cheap second half if grouping the state
alone does not get it under.

## Risks

- Readability only. Every field is set from exactly one control and read in exactly one place.

## Suggested Solutions

- One `useState<AccountFields>` for the nine scalars plus `patch`, leaving `role`, `reviewer`, `busy`
  and `error` — four hooks. A mechanical change; the form tests cover every submitted field.
- If the file is still over 250 lines afterwards, lift the two reviewer-only blocks (Conversation
  level, May command this bot) into a `ReviewerFieldsSection` component taking `reviewer` and
  `patchReviewer`. That is the only section with a natural seam; the rest of the form is one flow.
