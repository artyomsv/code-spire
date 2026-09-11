import { useState } from 'react';
import { deleteProvider, type ProviderView } from '../api';
export function DeleteConfirmModal({
  provider,
  onClose,
  onDeleted,
}: {
  provider: ProviderView;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await deleteProvider(provider.id);
      onDeleted();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-overlay">
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>Delete account</h3>
          <button className="iconbtn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <div className="modal-body">
          <p style={{ margin: 0, fontSize: 13.5, color: 'var(--text)' }}>
            Delete <strong>{provider.name}</strong>? This removes its stored token and cannot be undone.
          </p>
          <p className="prov-sub">Used by: {provider.usedBy?.join(', ') || (provider.usedBy ? 'No roles or sources' : 'Usage unavailable')}
            . If a context source references this account, reassign or remove that source first.
          </p>
          {error && <div className="modal-msg modal-error">{error}</div>}
          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="button" className="btn btn-danger" onClick={remove} disabled={busy}>
              {busy ? 'Deleting…' : 'Delete'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
