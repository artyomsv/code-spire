import type { ReactNode } from 'react';
import { X } from 'lucide-react';

/** One tab in the panel's own tab bar. `count` renders beside the label when it is a number. */
export interface PanelTab {
  id: string;
  label: string;
  count?: number;
}

interface Props {
  title: string;
  /** Read-only context under the title — coordinates, origin, scope. Never a control. */
  subtitle?: ReactNode;
  /** Locks every control inside while a save is in flight, so a second click cannot submit twice. */
  busy: boolean;
  onClose: () => void;
  /** Omit for a single-section panel. Two or more sections earn a tab bar. */
  tabs?: PanelTab[];
  tab?: string;
  onTab?: (id: string) => void;
  /** For a panel that carries a table of its own, which a form-width column would squeeze. */
  wide?: boolean;
  /** The footer controls. Put the primary action first. */
  actions: ReactNode;
  children: ReactNode;
}

/**
 * The shared side panel for reading one row and for changing it. A settings screen lists what
 * exists at full width; the panel slides in over the right-hand side, so a detail view never
 * renders below the list where it cannot be found, and a mutation form never renders inline
 * beside the list it changes.
 *
 * <p>The form is a `fieldset` on purpose: `disabled` on it locks every descendant control for the
 * duration of a save, which a `div` cannot do. The tab bar sits inside it for the same reason —
 * switching section mid-save would show controls that look editable and are not. `form-lock` only
 * removes the fieldset's native border and legend spacing; the element is kept for what it does,
 * not for how it looks.
 */
export default function SidePanel({ title, subtitle, busy, onClose, tabs, tab, onTab, wide, actions, children }: Props) {
  return (
    <div className="panel-overlay">
      <div className={wide ? 'side-panel wide' : 'side-panel'} onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={title}>
        <div className="panel-head">
          <div className="panel-heading">
            <h3>{title}</h3>
            {subtitle && <div className="panel-sub">{subtitle}</div>}
          </div>
          {/* Outside the fieldset, so the save lock is repeated: closing mid-save leaves an answer nobody sees land. */}
          <button className="iconbtn" type="button" onClick={onClose} aria-label="Close" disabled={busy}>
            <X size={15} aria-hidden="true" />
          </button>
        </div>
        <fieldset className="panel-form form-lock" aria-label={title} disabled={busy}>
          {tabs && tabs.length > 1 && (
            <div className="panel-tabs" role="tablist" aria-label={`${title} sections`}>
              {tabs.map(entry => (
                <button key={entry.id} type="button" role="tab" aria-selected={entry.id === tab}
                  className={entry.id === tab ? 'panel-tab on' : 'panel-tab'} onClick={() => onTab?.(entry.id)}>
                  {entry.label}
                  {typeof entry.count === 'number' && <span className="panel-tab-n">{entry.count}</span>}
                </button>
              ))}
            </div>
          )}
          <div className="panel-body">{children}</div>
        </fieldset>
        <div className="panel-actions">{actions}</div>
      </div>
    </div>
  );
}
