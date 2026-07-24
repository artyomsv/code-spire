# Reusable `Select` Dropdown — Design

**Date:** 2026-07-23
**Status:** Approved (brainstorm), pending implementation plan

## Goal

Replace the app's native `<select>` elements with one reusable, on-brand, accessible dropdown
component (`Select`) that matches the spire-ui design, handles long option labels, and — because its
list is portalled — is never clipped by a modal or card. Migrate all 13 native selects to it.

## Why

The native `<select>` clips long labels (e.g. the Add-webhook provider `name · type · workspace`),
its popup overflows the modal, and it doesn't match the app's dark, token-driven design. There are
**13 native selects across 5 files**; a single styled component fixes the look everywhere and removes
the clipping.

## Approach (chosen)

Hand-roll the component — no new dependency — reusing the app's existing patterns: the portalled
`position:fixed` popover from `Tooltip.tsx` (escapes `overflow:hidden`) and the CSS design tokens.
Own the accessibility (keyboard + ARIA) and test it. Then migrate every native select.

## Component: `Select` (`spire-ui/src/components/Select.tsx`)

### API

```ts
export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

interface SelectProps {
  value: string;
  options: SelectOption[];
  onChange: (value: string) => void;
  placeholder?: string;   // shown (muted) when value matches no option
  disabled?: boolean;
  ariaLabel?: string;     // when there's no visible <label> association
  id?: string;            // for a wrapping <label>'s htmlFor
  className?: string;
}
```

`value` is always a string (every current select uses string values). The component is controlled.

### Structure

- **Trigger** — a `<button type="button">` styled like the app's fields: `--panel-2` bg, `--border`,
  `--iris` focus ring, a lucide `ChevronDown` on the right that rotates when open. It shows the
  selected option's `label` (or the `placeholder`, muted) truncated with `text-overflow: ellipsis`
  and a `title` so long text never overflows the field. `width: 100%` (fills its `.field` column).
- **Listbox** — rendered via `createPortal` to `document.body`, `position: fixed`, only mounted while
  open. Positioned from the trigger's `getBoundingClientRect()`.

### Positioning & sizing

- `left` = trigger left; `top` = trigger bottom + 4px. If the list would overflow the viewport
  bottom, flip above the trigger instead.
- `min-width` = trigger width; `width: max-content` capped at `min(380px, viewport - 16px)`, so long
  labels get more room than the (possibly narrow) trigger/modal. `max-height: 280px` with
  `overflow-y: auto`. Options ellipsize + carry a `title` if still too long.
- While open, reposition on `scroll`/`resize` (capture-phase listeners); close on Escape,
  outside-click (`pointerdown` outside trigger+list), and blur leaving the widget.

### Keyboard & ARIA

- Trigger: `role="combobox"`, `aria-haspopup="listbox"`, `aria-expanded`, `aria-controls`,
  `aria-activedescendant` (the highlighted option's id). Enter / Space / ArrowDown / ArrowUp open it
  (ArrowDown highlights the first enabled or selected option; ArrowUp the last).
- Open: ArrowUp/Down move the highlight (skipping `disabled`), Home/End jump to first/last enabled,
  printable-character **type-ahead** jumps to the next option whose label starts with the typed
  string, Enter selects the highlighted option (calls `onChange`, closes, returns focus to the
  trigger), Escape closes without change, Tab closes and lets focus move on. Clicking an option
  selects it.
- Listbox: `role="listbox"`; each option `role="option"`, `aria-selected={value===opt.value}`, a
  stable `id`, and an `aria-disabled` when disabled. The highlighted option gets an `active` class.
- `disabled` Select: trigger is `disabled` (not focusable), greyed; never opens.
- `prefers-reduced-motion`: no open/close transition.

### Styling (append to `index.css`)

New classes only, using existing tokens (mirror `.field select` for the trigger and
`.info-pop`/`.tooltip` for the popover): `.select` (trigger), `.select[aria-expanded="true"]`,
`.select .select-val` (truncate), `.select .select-chev`; `.select-pop` (portalled listbox),
`.select-opt`, `.select-opt.active` (`--hover`/`--iris-soft`), `.select-opt[aria-selected="true"]`
(`--iris-ink`), `.select-opt.disabled`. Both light and dark themes already covered by the tokens.

## Migration — all 13 native selects

Replace each `<select value onChange>…</select>` with `<Select value options=… onChange=… ariaLabel=… />`,
building the `options` array from the existing `<option>`s / mapped data. Keep the surrounding
`<label className="field"><span>…</span> … </label>` wrapper; the `Select` replaces only the inner
`<select>`. Pass an `ariaLabel` (or wire `id` to the label) so each control stays labelled.

| File | Selects |
|---|---|
| `SettingsWebhookRepos.tsx` | provider (from `providers`), scope (Repository/Organization) |
| `SettingsProviders.tsx` | type (3), authKind (bearer/basic), conversationLevel |
| `SettingsLlmProviders.tsx` | type, model, reasoningEffort, + the two others (5 total) |
| `SettingsContextProviders.tsx` | type, authKind |
| `ConversationSettings.tsx` | level |

Dynamic option lists (providers, models) map straight to `SelectOption[]`. `onChange` receives the
value string (drop the `e.target.value` unwrapping).

## Testing

- **`Select.test.tsx`** (vitest + testing-library): shows the selected label; opens on trigger click
  and on ArrowDown; lists options; clicking an option calls `onChange(value)` and closes; keyboard
  ArrowDown→Enter selects; Escape closes without `onChange`; a `disabled` option is not selectable;
  the trigger exposes `role="combobox"` + `aria-expanded`; `disabled` Select doesn't open.
- **Migrated-form tests:** the custom `Select` renders options only while open, and its trigger is a
  button (not a native `<select>`). Any existing test that queried a native option (notably
  `SettingsWebhookRepos.form.test.tsx`, which asserts the provider options and picks a slug) must be
  updated to open the `Select` first, then assert/select. Update every test that interacts with a
  migrated select; the full `spire-ui` vitest suite must stay green.
- `tsc --noEmit` clean.

## Success criteria

1. One `Select` component; no native `<select>` remains in `src/components`.
2. Long labels (the provider `name · type · workspace`) are fully readable in the open list and never
   clip the field or overflow the modal.
3. Keyboard + screen-reader usable (combobox/listbox roles, arrow/enter/esc/type-ahead).
4. Looks on-brand in light and dark themes.
5. `Select.test.tsx` green; all migrated-form tests updated and green; `tsc --noEmit` clean.

## Non-goals

- No multi-select, no async/searchable/creatable combobox (YAGNI — every current use is a small fixed
  or provider/model list). Type-ahead covers quick finding.
- No new npm dependency.
- No grouped options / custom per-option rendering (none of the 13 need it).
- Not touching non-`<select>` inputs (text, checkbox, textarea) or the sidebar review-mode toggle.

## Risks / notes

- **Portal + modal focus:** the listbox is portalled outside the modal DOM; outside-click close must
  treat the portalled list as "inside" (check both trigger and list refs) so opening it doesn't
  close the modal. Covered by the pointerdown-target check.
- **Test churn:** migrating breaks tests that query native `<option>`; that's expected and in-scope
  (the plan updates them).
- File-size: `SettingsLlmProviders.tsx`/`SettingsProviders.tsx` are already large; this migration
  only swaps elements (roughly net-neutral lines), no new split required here.
