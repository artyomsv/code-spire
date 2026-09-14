import type { ReactNode } from 'react';

interface Props {
  title: string;
  /** Locks every control inside while a save is in flight, so a second click cannot submit twice. */
  busy: boolean;
  onClose: () => void;
  /** The footer controls. Put the primary action first. */
  actions: ReactNode;
  children: ReactNode;
}

/**
 * The shared dialog for creating and editing. A settings screen lists what exists; changing it
 * happens here, so a mutation form never renders inline beside the list it changes.
 *
 * <p>The body is a `fieldset` on purpose: `disabled` on it locks every descendant control for the
 * duration of a save, which a `div` cannot do. `form-lock` only removes the fieldset's native
 * border and legend spacing — the element is kept for what it does, not for how it looks.
 */
export default function FormDialog({ title, busy, onClose, actions, children }: Props) {
  return (
    <div className="modal-overlay">
      <div className="modal wide" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{title}</h3>
          <button className="iconbtn" type="button" onClick={onClose} aria-label="Close">✕</button>
        </div>
        <fieldset className="modal-body scroll form-lock" aria-label={title} disabled={busy}>
          {children}
        </fieldset>
        <div className="modal-actions">{actions}</div>
      </div>
    </div>
  );
}
