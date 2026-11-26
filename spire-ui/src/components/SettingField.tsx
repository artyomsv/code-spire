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
  /** The control's id. When given, the visible label names that control explicitly (`htmlFor`), so its accessible
   *  name is the label alone; wrapped in one `<label>` instead, the info button is the first control inside it and
   *  takes the label (PR 456, M-2). */
  inputId?: string;
  children: ReactNode;
}

/**
 * A settings field: its label, an info icon carrying the explanation, and the control. The icon is a
 * real button so the hint is reachable by keyboard, not hover-only.
 */
export default function SettingField({ label, hint, scope, inputId, children }: Props) {
  const info = (
    <Tooltip label={hint}>
      <button type="button" className="field-info" aria-label={`About ${label.toLowerCase()} — ${scope}`}>
        <Info size={12} aria-hidden="true" />
      </button>
    </Tooltip>
  );
  if (inputId) {
    return (
      <div className="field field-compact">
        <span className="field-label">
          <label htmlFor={inputId}>{label}</label>
          {info}
        </span>
        {children}
      </div>
    );
  }
  return (
    <label className="field field-compact">
      <span className="field-label">
        {label}
        {info}
      </span>
      {children}
    </label>
  );
}
