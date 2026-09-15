# Shared widgets and presentation vocabulary

**Before writing a new widget, check WIDGETS.md first.** Reuse the components and classes below;
check this before writing a new widget or replacing a screen. Copy a working screen's interaction
as well as its appearance: labels, empty states, pending-save lock-out and success feedback matter.

| Use | Shared vocabulary / helper | Working reference |
|---|---|---|
| Screen and list | `content`, `card`, `prov-head`, `prov-title`, `prov-note`, `prov-actions`; list first, Add opens the form | [RepositoryRegistryPage](../src/components/repositories/RepositoryRegistryPage.tsx), [SettingsWebhookRepos](../src/components/SettingsWebhookRepos.tsx) |
| Tables | `prov-scroll` wraps `prov-table`; `prov-name`, `prov-sub`, `nowrap` keep cells readable | [RepositoryRegistryPage](../src/components/repositories/RepositoryRegistryPage.tsx) |
| Forms | `field`, `field-hint`, `field-optional`, `field-check`, `field-row-2`, `field-sep`, `panel-body`; mark required/optional/automatic, retain native `required`, connect explanations with `aria-describedby` | [SourceForms](../src/components/repositories/factory/SourceForms.tsx), [RepositoryForm](../src/components/repositories/RepositoryForm.tsx) |
| Buttons | `btn` for the main action, `btn-ghost` for secondary actions, `prov-actions` for spacing; keep every control disabled during save, including Cancel | [SourceForms](../src/components/repositories/factory/SourceForms.tsx) |
| Empty lists | `wh-empty`, `wh-empty-icon`, `wh-empty-title`, `wh-empty-text`; explain the next action | [SettingsWebhookRepos](../src/components/SettingsWebhookRepos.tsx), [WorkPolicies](../src/components/work-items/WorkPolicies.tsx) |
| Badges | `chips` groups `chip` states; names remain text or links, never badge-shaped buttons | [AccountsCells](../src/components/AccountsCells.tsx) |
| Identifiers and account pairs | `mono nowrap` for short coordinates; `serving-pair` vertically aligns role/account badges | [RepositoryAccountsCell](../src/components/repositories/RepositoryAccountsCell.tsx) |
| Webhook path | Preserve the heading “Webhook path”; `wh-url` truncates at 180px; copy exposes the full value | [SettingsWebhookRepos](../src/components/SettingsWebhookRepos.tsx) |
| Credential account choices | `accountOptionLabel(account)` produces name · type · role, including disabled state. Use in **every** native option or shared Select option, including repository roles | [accounts.ts](../src/components/accounts.ts), [SettingsContextProviders](../src/components/SettingsContextProviders.tsx) |
| Account metadata | `roleLabel`, `hostOf`, `scopeLabel`, `accountKind`; do not recreate role or unknown-value handling | [accounts.ts](../src/components/accounts.ts), [AccountsCells](../src/components/AccountsCells.tsx) |
| Person identity | `ActorPicker`, `actorLabel`; people are stable provider IDs with display metadata, distinct from credential accounts | [ActorPicker](../src/components/ActorPicker.tsx) |
| Select menu | Shared `Select` provides keyboard navigation, type-ahead and a portalled listbox. Native selects also use `field`; explain missing prerequisites and empty options | [Select](../src/components/Select.tsx), [SettingsContextProviders](../src/components/SettingsContextProviders.tsx) |
| Copyable values | `CopyField`, `CopyableValue`; avoid full URLs as button labels | [CopyField](../src/components/CopyField.tsx), [RepositoryDetail](../src/components/repositories/RepositoryDetail.tsx) |
| Confirmation | `ConfirmDialog`; retain pending state and explicit action names | [ConfirmDialog](../src/components/ConfirmDialog.tsx) |
| Navigation | A visible icon with `ic`, size 16, alongside the page name | [App](../src/App.tsx) |
| **Create, edit or inspect one row** | `SidePanel`; a screen lists at full width, a panel beside it changes. Its form is a `fieldset` so `disabled` locks every control during a save. `tabs` splits a panel that does more than one job; `wide` suits a panel that carries a table | [SidePanel](../src/components/SidePanel.tsx), [RepositoryRegistryPage](../src/components/repositories/RepositoryRegistryPage.tsx) |
| **Setup made of parts that depend on each other** | `FactoryStep` in an ordered list, under a one-sentence outcome that says what the parts do together or which part is missing first. Each part is changed in place in a locking `fieldset`, one at a time; the number is real order, never decoration | [RepositoryFactory](../src/components/repositories/factory/RepositoryFactory.tsx), [FactoryOutcome](../src/components/repositories/factory/FactoryOutcome.tsx) |
| A one-time secret | `WebhookSecretReveal`; centred and modal on purpose, because dismissing it by accident means rotating the secret. Name the events the forge must send | [WebhookSecretReveal](../src/components/repositories/WebhookSecretReveal.tsx) |
| A profile’s eight phase modes | `PhaseStrip` and `PhaseLegend`; colour says who decides — grey nobody, amber a person, green the machine. Eight chips in one cell could not be compared between rows | [PhaseStrip](../src/components/work-items/PhaseStrip.tsx), [WorkPolicies](../src/components/work-items/WorkPolicies.tsx) |
| Something waiting for the operator | `attn`; a banner above the list. Below it, an operator reported not finding it at all | [RepositoryRegistryPage](../src/components/repositories/RepositoryRegistryPage.tsx) |
| **A control and its explanation** | `SettingField`; the hint sits on an info control, **never as prose under the input** — six controls must not become six paragraphs | [SettingField](../src/components/SettingField.tsx), [CapSettings](../src/components/CapSettings.tsx) |
| Hover or focus explanation | `Tooltip`; portalled, so a card's `overflow: hidden` cannot clip it | [Tooltip](../src/components/Tooltip.tsx) |

Styles live in [index.css](../src/index.css). A save must show the returned record in its list and
confirm success; errors retain the entered values. A derived value says what fills it and how to
change it. No choices means a reason and a next step, not a silent empty dropdown.

[settingsTables.contract.test.ts](../src/settingsTables.contract.test.ts) derives settings routes
and rendered child components before checking actual tables. [accountOptions.contract.test.ts](../src/accountOptions.contract.test.ts)
derives routed screens and children, identifies credential-account fields in ProviderView consumers,
and checks each native/shared Select option separately. A helper import alone cannot satisfy it.
Observed-person identity pickers have a different data model and do not use credential account labels.
Both guards assert that they discovered real controls before concluding. Preserve their production
mutation proofs when changing the source traversal or extracting components. Comment removal must
preserve quoted paths such as `/runs/*`, so a wildcard cannot hide later routes from either guard.

`settingsTables.contract.test.ts` also holds three conventions: every settings file with form controls
must use `SettingField`, except `FIELD_COMPONENTS` that are themselves one control; a settings screen must not render its own `<form>`; and create and edit
open in `SidePanel`, anchored to an edge, never a centred `modal-overlay`. That last rule asserts
the panel component and its stylesheet as well as the screens — reading the screens alone let a
mutation that gave `SidePanel` the centred overlay class pass every test, because the class lives
in the shared component and not in the screen. All are mutation-verified in place.

The field rule was first written for screens alone. When create and edit moved into side panels and
the Factory tab's step forms — none of which is a screen — it inspected nothing and still passed.
It now covers every rendered settings file and asserts it found real forms before concluding.

## The legacy list

`LEGACY_SCREENS` in that guard names screens written before these conventions. **It may shrink and
must never grow.** A third test fails if an entry no longer owes anything, so a fixed screen cannot
sit on the list — otherwise the list quietly becomes permission to stay broken. A new entry means a
new screen repeated a mistake these rules exist to prevent; fix the screen instead.

One exception is recorded: on 2026-09-15 three field groups of listed legacy forms were added
(`AccountCredentialFields`, `SettingsLlmModelRateFields`, `SettingsLlmModelDialectFields`). They
were always in that debt; the widened field rule is what first saw them. They leave with their forms.
