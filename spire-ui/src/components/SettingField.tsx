import type { ReactNode } from 'react';
import { Info } from 'lucide-react';
import Tooltip from './Tooltip';

interface Props {
  label: string;
  /** The explanation — shown on hover/focus of the info icon instead of as prose under the input, so a
   *  form of six knobs does not become six paragraphs. */
  hint: string;
  /** Which group the field belongs to. Both groups own a "Retry attempts", so without this their info
   *  controls would share one accessible name and be indistinguishable to a screen reader. */
  scope: string;
  children: ReactNode;
}

/**
 * A settings field: its label, an info icon carrying the explanation, and the control. The icon is a
 * real button so the hint is reachable by keyboard, not hover-only.
 */
export default function SettingField({ label, hint, scope, children }: Props) {
  return (
    <label className="field field-compact">
      <span className="field-label">
        {label}
        <Tooltip label={hint}>
          <button
            type="button"
            className="field-info"
            aria-label={`About ${label.toLowerCase()} — ${scope}`}
          >
            <Info size={12} aria-hidden="true" />
          </button>
        </Tooltip>
      </span>
      {children}
    </label>
  );
}
