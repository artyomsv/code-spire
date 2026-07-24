# Reusable `Select` Dropdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one on-brand, accessible `Select` dropdown and replace all 13 native `<select>`s in `spire-ui` with it.

**Architecture:** A hand-rolled controlled `Select` (styled trigger button + a `createPortal` `position:fixed` listbox, à la `Tooltip`, so it escapes modal/card clipping) with full keyboard + ARIA. Then a per-file migration of every native select to it. No new dependency.

**Tech Stack:** React 19 / TypeScript / Vite, lucide-react icons, vitest + testing-library, CSS custom-property design tokens.

## Global Constraints

- No new npm dependency; hand-rolled.
- `Select` is controlled: `value: string`, `onChange: (value: string) => void`. Every current select uses string values.
- Theme-aware via existing tokens only (`--panel`, `--panel-2`, `--border`, `--border-strong`, `--text`, `--text-2`, `--text-3`, `--iris`, `--iris-soft`, `--iris-ink`, `--hover`, `--shadow`). Both light and dark are covered by the tokens.
- The listbox is portalled to `document.body`; outside-click close MUST treat the portalled list as "inside" (check trigger AND list refs) so opening it never closes a surrounding modal.
- Accessible: trigger `role="combobox"` + `aria-haspopup/expanded/controls/activedescendant`; list `role="listbox"`; options `role="option"` + `aria-selected`. Keyboard: ArrowUp/Down (skip disabled), Home/End, Enter, Escape, Tab, printable type-ahead. Focus stays on the trigger (activedescendant pattern).
- Every migrated `Select` gets an `ariaLabel` (the field's visible label text) — a `<label>` wrapping a button does not name it.
- TS: 2-space indent, `interface` for object shapes. Keep each `<label className="field"><span>…</span> … </label>` wrapper; the `Select` replaces only the inner `<select>`.
- After each task: `npx vitest run` (full `spire-ui` suite) + `npx tsc --noEmit`, both green. Run from the `spire-ui` dir.

---

## File Structure

- **Create** `spire-ui/src/components/Select.tsx` — the component.
- **Create** `spire-ui/src/components/Select.test.tsx` — its tests.
- **Modify** `spire-ui/src/index.css` — `.select` / `.select-pop` / `.select-opt` styles.
- **Modify** (migrate selects): `SettingsWebhookRepos.tsx` (2), `SettingsProviders.tsx` (3), `SettingsLlmProviders.tsx` (5), `SettingsContextProviders.tsx` (2), `ConversationSettings.tsx` (1).
- **Modify** `SettingsWebhookRepos.form.test.tsx` — open the provider `Select` before asserting its options (the only render-test that queries native options; `SettingsProviders.test.ts` / `SettingsLlmProviders.test.ts` are pure-logic, 0 render calls).

---

## Task 1: The `Select` component

**Files:**
- Create: `spire-ui/src/components/Select.tsx`
- Create: `spire-ui/src/components/Select.test.tsx`
- Modify: `spire-ui/src/index.css`

**Interfaces:**
- Produces: `export default function Select`; `export interface SelectOption { value: string; label: string; disabled?: boolean }`. Props: `{ value: string; options: SelectOption[]; onChange: (value: string) => void; placeholder?: string; disabled?: boolean; ariaLabel?: string; id?: string; className?: string }`. Consumed by Tasks 2–5.

- [ ] **Step 1: Write the failing tests**

Create `Select.test.tsx`:
```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import Select, { type SelectOption } from './Select';

const OPTS: SelectOption[] = [
  { value: 'a', label: 'Apple' },
  { value: 'b', label: 'Banana' },
  { value: 'c', label: 'Cherry', disabled: true },
];

function setup(value = 'a') {
  const onChange = vi.fn();
  render(<Select value={value} options={OPTS} onChange={onChange} ariaLabel="Fruit" />);
  return { onChange, trigger: screen.getByRole('combobox', { name: /fruit/i }) };
}

describe('Select', () => {
  it('shows the selected option label and is collapsed', () => {
    const { trigger } = setup('b');
    expect(trigger).toHaveTextContent('Banana');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('opens on click and lists options', () => {
    const { trigger } = setup();
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('option', { name: 'Banana' })).toBeInTheDocument();
  });

  it('selecting an option calls onChange and closes', () => {
    const { onChange, trigger } = setup();
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('option', { name: 'Banana' }));
    expect(onChange).toHaveBeenCalledWith('b');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('ArrowDown then Enter selects the next enabled option', () => {
    const { onChange, trigger } = setup('a');
    fireEvent.keyDown(trigger, { key: 'ArrowDown' }); // opens, highlights selected (a)
    fireEvent.keyDown(trigger, { key: 'ArrowDown' }); // -> b
    fireEvent.keyDown(trigger, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith('b');
  });

  it('does not select a disabled option', () => {
    const { onChange, trigger } = setup();
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('option', { name: 'Cherry' }));
    expect(onChange).not.toHaveBeenCalled();
  });

  it('Escape closes without changing', () => {
    const { onChange, trigger } = setup();
    fireEvent.click(trigger);
    fireEvent.keyDown(trigger, { key: 'Escape' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('a disabled Select does not open', () => {
    const onChange = vi.fn();
    render(<Select value="a" options={OPTS} onChange={onChange} ariaLabel="Fruit" disabled />);
    const trigger = screen.getByRole('combobox', { name: /fruit/i });
    fireEvent.click(trigger);
    expect(screen.queryByRole('option')).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run (from `spire-ui`): `npx vitest run src/components/Select.test.tsx`
Expected: FAIL — `./Select` does not resolve.

- [ ] **Step 3: Implement the component**

Create `Select.tsx`:
```tsx
import { useCallback, useEffect, useId, useRef, useState, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import { ChevronDown } from 'lucide-react';

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

interface SelectProps {
  value: string;
  options: SelectOption[];
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  ariaLabel?: string;
  id?: string;
  className?: string;
}

/**
 * On-brand accessible single-select. The trigger is a styled button; the listbox is portalled to
 * <body> and fixed-positioned from the trigger rect (like Tooltip), so it escapes modal/card overflow
 * clipping and can be wider than a narrow trigger. Focus stays on the trigger (aria-activedescendant
 * pattern); keyboard: arrows / Home / End / Enter / Escape / type-ahead. Outside-click + Escape close.
 */
export default function Select({
  value, options, onChange, placeholder, disabled, ariaLabel, id, className,
}: SelectProps) {
  const [open, setOpen] = useState(false);
  const [rect, setRect] = useState<DOMRect | null>(null);
  const [active, setActive] = useState(-1);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  const typeahead = useRef({ text: '', at: 0 });
  const baseId = useId();

  const selectedIndex = options.findIndex((o) => o.value === value);
  const selected = selectedIndex >= 0 ? options[selectedIndex] : null;

  const place = useCallback(() => {
    const el = triggerRef.current;
    if (el) setRect(el.getBoundingClientRect());
  }, []);

  // next enabled index from `from` in direction dir, wrapping
  const step = useCallback((from: number, dir: 1 | -1) => {
    const n = options.length;
    for (let k = 1; k <= n; k += 1) {
      const i = (from + dir * k + n) % n;
      if (!options[i].disabled) return i;
    }
    return from;
  }, [options]);

  const openAt = useCallback((startAt: number) => {
    if (disabled) return;
    place();
    setActive(startAt);
    setOpen(true);
  }, [disabled, place]);

  const close = useCallback((focusTrigger = true) => {
    setOpen(false);
    setActive(-1);
    if (focusTrigger) triggerRef.current?.focus();
  }, []);

  const choose = useCallback((i: number) => {
    const opt = options[i];
    if (!opt || opt.disabled) return;
    onChange(opt.value);
    close();
  }, [options, onChange, close]);

  useEffect(() => {
    if (!open) return undefined;
    const reposition = () => place();
    const onPointer = (e: PointerEvent) => {
      const t = e.target as Node;
      if (triggerRef.current?.contains(t) || listRef.current?.contains(t)) return;
      setOpen(false);
      setActive(-1);
    };
    window.addEventListener('scroll', reposition, true);
    window.addEventListener('resize', reposition);
    document.addEventListener('pointerdown', onPointer, true);
    return () => {
      window.removeEventListener('scroll', reposition, true);
      window.removeEventListener('resize', reposition);
      document.removeEventListener('pointerdown', onPointer, true);
    };
  }, [open, place]);

  useEffect(() => {
    if (open && active >= 0) {
      const el = document.getElementById(`${baseId}-opt-${active}`);
      el?.scrollIntoView({ block: 'nearest' });
    }
  }, [open, active, baseId]);

  const openStart = () => (selectedIndex >= 0 ? selectedIndex : step(-1, 1));

  function onKeyDown(e: React.KeyboardEvent) {
    if (!open) {
      if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        openAt(openStart());
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        openAt(selectedIndex >= 0 ? selectedIndex : step(0, -1));
      }
      return;
    }
    switch (e.key) {
      case 'ArrowDown': e.preventDefault(); setActive((i) => step(i < 0 ? -1 : i, 1)); break;
      case 'ArrowUp': e.preventDefault(); setActive((i) => step(i < 0 ? 0 : i, -1)); break;
      case 'Home': e.preventDefault(); setActive(step(-1, 1)); break;
      case 'End': e.preventDefault(); setActive(step(0, -1)); break;
      case 'Enter': e.preventDefault(); if (active >= 0) choose(active); break;
      case 'Escape': e.preventDefault(); close(); break;
      case 'Tab': close(false); break;
      default:
        if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
          const now = Date.now();
          const text = now - typeahead.current.at < 600 ? typeahead.current.text + e.key : e.key;
          typeahead.current = { text, at: now };
          const lower = text.toLowerCase();
          const match = options.findIndex((o) => !o.disabled && o.label.toLowerCase().startsWith(lower));
          if (match >= 0) setActive(match);
        }
    }
  }

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        id={id}
        className={className ? `select ${className}` : 'select'}
        role="combobox"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? `${baseId}-list` : undefined}
        aria-activedescendant={open && active >= 0 ? `${baseId}-opt-${active}` : undefined}
        aria-label={ariaLabel}
        disabled={disabled}
        onClick={() => (open ? close() : openAt(openStart()))}
        onKeyDown={onKeyDown}
      >
        <span className={selected ? 'select-val' : 'select-val placeholder'} title={selected?.label}>
          {selected ? selected.label : placeholder ?? ''}
        </span>
        <ChevronDown className="select-chev" size={15} aria-hidden="true" />
      </button>
      {open && rect
        && createPortal(
          <ul ref={listRef} id={`${baseId}-list`} role="listbox" className="select-pop" style={popStyle(rect)}>
            {options.map((o, i) => (
              <li
                key={o.value}
                id={`${baseId}-opt-${i}`}
                role="option"
                aria-selected={o.value === value}
                aria-disabled={o.disabled || undefined}
                className={`select-opt${i === active ? ' active' : ''}${o.disabled ? ' disabled' : ''}`}
                title={o.label}
                onMouseEnter={() => { if (!o.disabled) setActive(i); }}
                onClick={() => choose(i)}
              >
                {o.label}
              </li>
            ))}
          </ul>,
          document.body,
        )}
    </>
  );
}

/** Fixed-position from the trigger rect; flips above when there's more room up top. */
function popStyle(rect: DOMRect): CSSProperties {
  const maxH = 280;
  const below = window.innerHeight - rect.bottom;
  const flip = below < Math.min(maxH, 200) && rect.top > below;
  return {
    position: 'fixed',
    left: rect.left,
    minWidth: rect.width,
    maxWidth: Math.min(380, window.innerWidth - 16),
    maxHeight: maxH,
    ...(flip ? { bottom: window.innerHeight - rect.top + 4 } : { top: rect.bottom + 4 }),
  };
}
```

- [ ] **Step 4: Add the styles**

In `index.css`, after the `.field select:focus` rule (`:852`), add:
```css
  /* Reusable custom Select — trigger mirrors .field select; the list is a portalled popover. */
  .select {
    display: flex; align-items: center; gap: 8px; width: 100%;
    background: var(--panel-2); border: 1px solid var(--border); border-radius: 8px;
    padding: 9px 11px; color: var(--text); font-size: 13px; font-family: var(--font-sans);
    text-align: left; cursor: pointer; outline: none;
  }
  .select:hover { border-color: var(--border-strong); }
  .select:focus-visible { border-color: var(--iris); box-shadow: 0 0 0 2px var(--iris-soft); }
  .select[aria-expanded="true"] { border-color: var(--iris); }
  .select:disabled { opacity: 0.55; cursor: default; }
  .select .select-val { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .select .select-val.placeholder { color: var(--text-3); }
  .select .select-chev { flex: none; color: var(--text-3); transition: transform 0.15s ease; }
  .select[aria-expanded="true"] .select-chev { transform: rotate(180deg); }

  .select-pop {
    z-index: 60; margin: 0; padding: 4px; list-style: none;
    background: var(--panel); border: 1px solid var(--border-strong); border-radius: 9px;
    box-shadow: var(--shadow); overflow-y: auto; overscroll-behavior: contain;
  }
  .select-opt {
    padding: 8px 10px; border-radius: 6px; font-size: 13px; color: var(--text); cursor: pointer;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  .select-opt.active { background: var(--hover); }
  .select-opt[aria-selected="true"] { color: var(--iris-ink); background: var(--iris-soft); }
  .select-opt.disabled { color: var(--text-3); cursor: default; }
  @media (prefers-reduced-motion: reduce) { .select .select-chev { transition: none; } }
```

- [ ] **Step 5: Run to verify pass**

Run: `npx vitest run src/components/Select.test.tsx && npx tsc --noEmit`
Expected: PASS (7 tests) + no type errors.

- [ ] **Step 6: Commit**

```bash
git add spire-ui/src/components/Select.tsx spire-ui/src/components/Select.test.tsx spire-ui/src/index.css
git commit -m "Add reusable accessible Select dropdown"
```

---

## Task 2: Migrate `SettingsWebhookRepos.tsx` (+ its form test)

**Files:**
- Modify: `spire-ui/src/components/SettingsWebhookRepos.tsx`
- Modify: `spire-ui/src/components/SettingsWebhookRepos.form.test.tsx`

**Interfaces:** Consumes `Select` (Task 1).

**Context:** Two selects in `WebhookRepoFormModal`: the provider picker (rendered only when NOT `legacyEdit`) and the scope select (`disabled={legacyEdit}`). Order matters: migrating first makes the existing form test go RED (the custom `Select` renders options only when open — a native `<select>` always has its `<option>`s in the DOM), then the test update fixes it.

- [ ] **Step 1: Import `Select` and replace both selects**

Add `import Select from './Select';` to `SettingsWebhookRepos.tsx`.

Replace the provider `<select>` (the non-legacy branch) with:
```tsx
                    <Select
                      ariaLabel="Provider"
                      value={providerId}
                      options={providers.map((p) => ({ value: p.id, label: `${p.name} · ${p.type} · ${p.workspace}` }))}
                      onChange={setProviderId}
                    />
```

Replace the scope `<select>` with:
```tsx
                  <Select
                    ariaLabel="Scope"
                    value={scope}
                    options={SCOPES.map((s) => ({ value: s.value, label: s.label }))}
                    onChange={(v) => setScope(v as WebhookScope)}
                    disabled={legacyEdit}
                  />
```

- [ ] **Step 2: Run the form test — verify it now fails**

Run: `npx vitest run src/components/SettingsWebhookRepos.form.test.tsx`
Expected: FAIL — the first test (`lists registered providers…`) asserts `getByRole('option', { name: /Acme Bot · github · acme/ })`, but the custom `Select` renders no options until it is opened, so the option isn't in the DOM. The other four tests still pass.

- [ ] **Step 3: Update the first test to open the provider Select before asserting options**

In `SettingsWebhookRepos.form.test.tsx`, change only the first test:
```tsx
  it('lists registered providers and fixes the owner for repo scope', async () => {
    render(<SettingsWebhookRepos />);
    fireEvent.click((await screen.findAllByRole('button', { name: /add webhook/i }))[0]);
    // open the provider dropdown, then assert both are offered
    fireEvent.click(await screen.findByRole('combobox', { name: /provider/i }));
    await waitFor(() => expect(screen.getByRole('option', { name: /Acme Bot · github · acme/ })).toBeInTheDocument());
    expect(screen.getByRole('option', { name: /Lab Bot · gitlab · my-team/ })).toBeInTheDocument();
    expect(screen.getByText('acme/')).toBeInTheDocument();
  });
```
Leave the other four tests unchanged.

- [ ] **Step 4: Run form test + full suite + typecheck**

Run: `npx vitest run src/components/SettingsWebhookRepos.form.test.tsx && npx vitest run && npx tsc --noEmit`
Expected: PASS (all 5 form tests + full suite) + no type errors.

- [ ] **Step 5: Commit**

```bash
git add spire-ui/src/components/SettingsWebhookRepos.tsx spire-ui/src/components/SettingsWebhookRepos.form.test.tsx
git commit -m "Use Select for the webhook provider and scope dropdowns"
```

---

## Task 3: Migrate `SettingsProviders.tsx` (3 selects)

**Files:** Modify `spire-ui/src/components/SettingsProviders.tsx`

**Interfaces:** Consumes `Select` (Task 1).

- [ ] **Step 1: Import + replace the three selects**

Add `import Select from './Select';`.

Type (currently `<select value={type} onChange={(e) => changeType(e.target.value)}>{PROVIDER_TYPES.map(...)}`):
```tsx
              <Select
                ariaLabel="Type"
                value={type}
                options={PROVIDER_TYPES.map((t) => ({ value: t, label: t }))}
                onChange={changeType}
              />
```

Auth kind (`basic` only when `!BEARER_ONLY.has(type)`; disabled when bearer-only):
```tsx
              <Select
                ariaLabel="Auth kind"
                value={authKind}
                disabled={BEARER_ONLY.has(type)}
                options={[
                  { value: 'bearer', label: 'bearer' },
                  ...(BEARER_ONLY.has(type) ? [] : [{ value: 'basic', label: 'basic' }]),
                ]}
                onChange={(v) => setAuthKind(v as AuthKind)}
              />
```

Conversation level:
```tsx
            <Select
              ariaLabel="Conversation level"
              value={conversationLevel}
              options={CONVERSATION_OPTIONS.map((lvl) => ({ value: lvl, label: conversationLabel(lvl) }))}
              onChange={setConversationLevel}
            />
```

- [ ] **Step 2: Run full suite + typecheck**

Run: `npx vitest run && npx tsc --noEmit`
Expected: PASS + no type errors (`SettingsProviders.test.ts` is pure-logic — unaffected).

- [ ] **Step 3: Commit**

```bash
git add spire-ui/src/components/SettingsProviders.tsx
git commit -m "Use Select for the SCM provider form dropdowns"
```

---

## Task 4: Migrate `SettingsLlmProviders.tsx` (5 selects)

**Files:** Modify `spire-ui/src/components/SettingsLlmProviders.tsx`

**Interfaces:** Consumes `Select` (Task 1).

- [ ] **Step 1: Import + replace the five selects**

Add `import Select from './Select';`.

Provider-form **Type** (has a baseUrl side-effect on change):
```tsx
              <Select
                ariaLabel="Type"
                value={type}
                options={LLM_TYPES.map((t) => ({ value: t, label: t }))}
                onChange={(v) => {
                  const t = v as LlmType;
                  setType(t);
                  if (!baseUrl.trim() || Object.values(DEFAULT_BASE_URLS).includes(baseUrl)) {
                    setBaseUrl(defaultBaseUrl(t));
                  }
                }}
              />
```

Provider-form **Model** (keep the `typeModels.length > 0 ? … : <input>` conditional; replace only the `<select>` branch):
```tsx
                <Select
                  ariaLabel="Model"
                  value={model}
                  options={[
                    { value: '', label: '— select a model —' },
                    ...typeModels.map((m) => ({ value: m.name, label: `${m.label} (${m.name})` })),
                  ]}
                  onChange={setModel}
                />
```

Model-form **Type**:
```tsx
              <Select
                ariaLabel="Type"
                value={type}
                options={LLM_TYPES.map((t) => ({ value: t, label: t }))}
                onChange={(v) => setType(v as LlmType)}
              />
```

Model-form **Output token limit** (side-effect preset on change):
```tsx
                <Select
                  ariaLabel="Output token limit"
                  value={outputTokenParam}
                  options={TOKEN_PARAMS.map((t) => ({ value: t.value, label: t.label }))}
                  onChange={(v) => {
                    const next = v as OutputTokenParam;
                    setOutputTokenParam(next);
                    if (next === 'MAX_COMPLETION_TOKENS') setSupportsTemperature(false);
                  }}
                />
```

Model-form **Reasoning effort**:
```tsx
                <Select
                  ariaLabel="Reasoning effort"
                  value={reasoningEffort}
                  options={REASONING_EFFORTS.map((r) => ({ value: r, label: r === '' ? '— none —' : r }))}
                  onChange={setReasoningEffort}
                />
```

- [ ] **Step 2: Run full suite + typecheck**

Run: `npx vitest run && npx tsc --noEmit`
Expected: PASS + no type errors (`SettingsLlmProviders.test.ts` is pure-logic — unaffected).

- [ ] **Step 3: Commit**

```bash
git add spire-ui/src/components/SettingsLlmProviders.tsx
git commit -m "Use Select for the LLM provider and model form dropdowns"
```

---

## Task 5: Migrate `SettingsContextProviders.tsx` (2) + `ConversationSettings.tsx` (1)

**Files:**
- Modify: `spire-ui/src/components/SettingsContextProviders.tsx`
- Modify: `spire-ui/src/components/ConversationSettings.tsx`

**Interfaces:** Consumes `Select` (Task 1).

- [ ] **Step 1: `SettingsContextProviders.tsx` — import + replace both selects**

Add `import Select from './Select';`.

Type:
```tsx
              <Select
                ariaLabel="Type"
                value={type}
                options={CONTEXT_TYPES.map((t) => ({ value: t, label: t }))}
                onChange={(v) => setType(v as ContextType)}
              />
```

Auth (hardcoded two options):
```tsx
              <Select
                ariaLabel="Auth"
                value={authKind}
                options={[
                  { value: 'basic', label: 'basic · email + API token (Cloud)' },
                  { value: 'bearer', label: 'bearer · personal access token (Data Center)' },
                ]}
                onChange={(v) => setAuthKind(v as ContextAuthKind)}
              />
```

- [ ] **Step 2: `ConversationSettings.tsx` — import + replace the level select**

Add `import Select from './Select';`. Replace the `<select value={settings.level} disabled={busy} …>`:
```tsx
        <Select
          ariaLabel="Interaction level"
          value={settings.level}
          disabled={busy}
          options={LEVELS.map((l) => ({ value: l, label: LABELS[l] }))}
          onChange={(v) => update('level', normalizeLevel(v))}
        />
```

- [ ] **Step 3: Run full suite + typecheck + confirm no native selects remain**

Run: `npx vitest run && npx tsc --noEmit`
Expected: PASS + no type errors.
Run: `grep -rn "<select" src/components/`
Expected: no matches (every native select migrated).

- [ ] **Step 4: Commit**

```bash
git add spire-ui/src/components/SettingsContextProviders.tsx spire-ui/src/components/ConversationSettings.tsx
git commit -m "Use Select for the context provider and conversation dropdowns"
```

---

## Final verification (after all tasks)

- [ ] `grep -rn "<select" spire-ui/src/components/` → zero matches.
- [ ] From `spire-ui`: `npx tsc --noEmit && npx vitest run` — clean, all pass.
- [ ] Manual smoke (dev server on :34000): open Settings → Webhooks → Add webhook — the Provider dropdown shows full `name · type · workspace` labels in an on-brand list that isn't clipped by the modal; keyboard (arrows/Enter/Esc/type-ahead) works; light and dark both look right.

## Success criteria (from the spec)

1. One `Select` component; no native `<select>` remains in `src/components`.
2. Long provider labels are fully readable in the open list and never clip the field/modal.
3. Keyboard + screen-reader usable (combobox/listbox roles; arrows/enter/esc/type-ahead).
4. On-brand in light and dark.
5. `Select.test.tsx` green; migrated-form tests updated and green; `tsc --noEmit` clean.
