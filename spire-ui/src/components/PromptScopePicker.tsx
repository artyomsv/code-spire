import { useEffect, useState } from 'react';
import { fetchPromptScopes, GLOBAL_SCOPE } from '../api';

interface PromptScopePickerProps {
  value: string;
  onChange: (scope: string) => void;
  disabled?: boolean;
}

/**
 * Which repository's prompt overrides to view/edit -- `GLOBAL_SCOPE` (the deployment-wide
 * default) or one of the repositories this deployment has actually reviewed. A native `<select>`
 * on purpose, not the project's usual custom `Select`: this picker's correctness depends on the
 * browser's own display-value semantics (the caller navigates on change, so there is no benefit
 * to the custom combobox's portalled listbox here).
 */
export default function PromptScopePicker({ value, onChange, disabled }: PromptScopePickerProps) {
  const [scopes, setScopes] = useState<string[]>([]);

  useEffect(() => {
    let alive = true;
    // Best-effort: a failed fetch leaves the picker at Global rather than blocking the page --
    // every prompt endpoint already defaults to GLOBAL_SCOPE if this list can't be shown.
    fetchPromptScopes()
      .then((s) => alive && setScopes(s))
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  return (
    <label className="field prompt-scope-picker">
      <span>Scope</span>
      <select
        aria-label="Prompt scope"
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value={GLOBAL_SCOPE}>Global (all repositories)</option>
        {scopes.map((s) => (
          <option key={s} value={s}>
            {s}
          </option>
        ))}
      </select>
    </label>
  );
}
