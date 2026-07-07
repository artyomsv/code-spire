import { useState, type ReactNode } from 'react';

interface Props {
  title: string;
  message: ReactNode;
  confirmLabel: string;
  busyLabel?: string;
  danger?: boolean;
  onConfirm: () => Promise<void>;
  onClose: () => void;
}

/**
 * A small confirm/cancel modal for destructive actions. Runs the async
 * `onConfirm`, surfacing any error in-place and keeping the dialog open so the
 * user can retry or cancel; closes on success.
 */
export default function ConfirmDialog({
  title,
  message,
  confirmLabel,
  busyLabel,
  danger,
  onConfirm,
  onClose,
}: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      await onConfirm();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setBusy(false);
    }
  }

  return (
    <div className="modal-overlay" onClick={busy ? undefined : onClose}>
      <div className="modal modal-sm" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>{title}</h3>
          <button className="iconbtn" onClick={onClose} aria-label="Close" disabled={busy}>
            ✕
          </button>
        </div>
        <div className="modal-body">
          <div className="confirm-msg">{message}</div>
          {error && <div className="modal-msg modal-error">{error}</div>}
          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose} disabled={busy}>
              Cancel
            </button>
            <button
              type="button"
              className={danger ? 'btn btn-danger' : 'btn'}
              onClick={confirm}
              disabled={busy}
            >
              {busy ? (busyLabel ?? 'Working…') : confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
