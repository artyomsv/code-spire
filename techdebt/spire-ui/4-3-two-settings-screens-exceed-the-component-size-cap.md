# Two more settings screens are more than twice the component size cap

| Field | Value |
|-------|-------|
| Criticality | Low |
| Complexity | Medium |
| Location | `spire-ui/src/components/SettingsWebhookRepos.tsx` (602 lines), `spire-ui/src/components/SettingsContextProviders.tsx` (599 lines) |
| Found during | Accounts and roles — PR #120 rules-compliance review |
| Date | 2026-09-07 |

## Issue

The project's guideline is **250 lines per React component** (`~/.claude/rules/clean-code-react.md`).
Both files are roughly two and a half times that, and both grew on this branch:

| File | At `master` (`2b1ccb2`) | Now | Delta | What this branch added |
|---|---|---|---|---|
| `SettingsWebhookRepos.tsx` | 586 | **602** | +16 | the two serving-account columns (reviewer and factory) |
| `SettingsContextProviders.tsx` | 593 | **599** | +6 | the Used-by column |

Neither overage is this branch's doing — both were already more than double the cap before it —
and neither addition is large. They are recorded because no debt entry covered either file, so the
overage had no owner, and because the branch grew both rather than either.

Each file holds a page, a table and one or more modals in one module:

| File | Unit | Lines |
|---|---|---|
| `SettingsWebhookRepos.tsx` | `SettingsWebhookRepos` — the screen, including the row rendering | 40-214 |
| | `useWebhookProviders` — the form's provider lookup hook | 215-245 |
| | `WebhookRepoFormModal` — create/edit, with the in-flight verify | 246-477 |
| | `WebhookSetupChecklist`, `SecretRevealModal`, `DeleteConfirmModal` | 478-602 |
| `SettingsContextProviders.tsx` | `SettingsContextProviders` — the screen, including the row rendering | 147-330 |
| | `ConnCell` — connectivity-state cell | 331-361 |
| | `ContextProviderForm` — the create/edit form | 362-521 |
| | `PreviewModal` — the resolved-context preview | 522-599 |

## Risks

Low, and maintainability-only. Both screens work, both are covered by their own test files, and
nothing here is a correctness or credential risk.

The costs are the ones `techdebt/spire-ui/4-3-settings-llm-providers-exceeds-the-component-size-cap.md`
already sets out for the LLM screen, and they apply unchanged:

- **Every change to webhooks or to context sources serialises on one file**, so parallel work
  collides there.
- **Review attention is finite per file.** The in-flight verify guard this branch mirrored into
  `ServingCell` lives in `WebhookRepoFormModal`, three hundred lines into `SettingsWebhookRepos.tsx`;
  the reviewer who found the missing guard in the small new component had to read the large old one
  to know the idiom existed.

## Suggested Solutions

1. **Mirror the split this branch already performed on the Accounts screen** (page / table / form).
   `SettingsProviders.tsx` was decomposed into `SettingsProviders.tsx` (149), `AccountsTable.tsx`
   (177) and `ProviderFormModal.tsx` (429) — a pure move, reviewable as one, and the precedent is in
   the same directory. Applied here that is:
   - `SettingsWebhookRepos.tsx` → the screen, a `WebhookRepoTable`, and `WebhookRepoFormModal.tsx`
     carrying `useWebhookProviders`, `WebhookSetupChecklist`, `SecretRevealModal` and
     `DeleteConfirmModal` with it.
   - `SettingsContextProviders.tsx` → the screen, a `ContextProviderTable` (taking `ConnCell` with
     it), and `ContextProviderForm.tsx` (taking `PreviewModal`).
   Note what the Accounts split did NOT achieve: the extracted form is itself 429 lines, still over
   the cap. Moving the modals out is necessary and cheap; it is not sufficient, and whoever stops
   there should say so rather than let the commit read as closed.
2. **Do one file and re-measure.** `SettingsWebhookRepos.tsx` is the better first target: it is the
   larger of the two and the one this branch grew most.
3. **Leave them.** Honest only while neither grows — and both grew this branch, which is the reason
   this entry exists.
