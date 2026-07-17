import { useEffect, useState } from 'react';
import { discardDlqEntry, getDlqEntries, replayDlqEntry, type DlqEntry } from '../api';
import ConfirmDialog from './ConfirmDialog';

// First N characters shown of the payload — it can carry long encrypted-credential
// ciphertext, so the full value is never rendered (only sent server-side on replay).
const PAYLOAD_PREVIEW_LEN = 160;

function truncatePayload(payload: string): string {
  return payload.length > PAYLOAD_PREVIEW_LEN ? `${payload.slice(0, PAYLOAD_PREVIEW_LEN)}…` : payload;
}

export default function SettingsDlq() {
  const [entries, setEntries] = useState<DlqEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [rowErrors, setRowErrors] = useState<Record<string, string | null>>({});
  const [confirmDiscard, setConfirmDiscard] = useState<DlqEntry | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setEntries(await getDlqEntries(true));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  function removeEntry(id: string) {
    setEntries((prev) => prev.filter((e) => e.id !== id));
  }

  async function replay(entry: DlqEntry) {
    setBusyId(entry.id);
    setRowErrors((prev) => ({ ...prev, [entry.id]: null }));
    try {
      await replayDlqEntry(entry.id);
      removeEntry(entry.id);
    } catch (err) {
      setRowErrors((prev) => ({ ...prev, [entry.id]: err instanceof Error ? err.message : String(err) }));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <section className="content">
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">Dead-letter</h2>
        </div>

        {error ? (
          <div style={{ padding: '26px 18px', color: 'var(--crit)', fontSize: 13 }}>{error}</div>
        ) : loading && entries.length === 0 ? (
          <div style={{ padding: '26px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : entries.length === 0 ? (
          <div className="wh-empty">
            <div className="wh-empty-icon">
              <svg
                width="22"
                height="22"
                viewBox="0 0 16 16"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <rect x="2" y="2" width="12" height="12" rx="2.5" />
                <path d="M8 5.4v3.4" />
                <circle cx="8" cy="11" r="0.8" fill="currentColor" stroke="none" />
              </svg>
            </div>
            <div className="wh-empty-title">No dead-lettered messages</div>
            <p className="wh-empty-text">No dead-lettered messages — everything is flowing.</p>
          </div>
        ) : (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Topic</th>
                <th>Reason</th>
                <th>Payload</th>
                <th>Created</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry) => (
                <tr key={entry.id}>
                  <td style={{ fontWeight: 600 }}>{entry.messageType}</td>
                  <td className="mono" style={{ fontSize: 12, color: 'var(--text-2)' }}>
                    {entry.originalTopic}
                  </td>
                  <td className="prov-sub">{entry.reason ?? '—'}</td>
                  <td
                    className="mono"
                    style={{ fontSize: 11, color: 'var(--text-3)', maxWidth: 320, overflowWrap: 'anywhere' }}
                  >
                    {truncatePayload(entry.payload)}
                  </td>
                  <td className="mono" style={{ fontSize: 12, color: 'var(--text-2)' }}>
                    {new Date(entry.createdAt).toLocaleString()}
                  </td>
                  <td>
                    <div className="prov-actions">
                      <button
                        type="button"
                        className="btn-ghost"
                        onClick={() => void replay(entry)}
                        disabled={busyId === entry.id}
                      >
                        {busyId === entry.id ? 'Replaying…' : 'Replay'}
                      </button>
                      <button
                        type="button"
                        className="btn-ghost danger"
                        onClick={() => setConfirmDiscard(entry)}
                        disabled={busyId === entry.id}
                      >
                        Discard
                      </button>
                    </div>
                    {rowErrors[entry.id] && <div className="conn-detail">{rowErrors[entry.id]}</div>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {confirmDiscard && (
        <ConfirmDialog
          title="Discard dead-letter entry?"
          message={
            <p>
              Discard the <strong>{confirmDiscard.messageType}</strong> message from{' '}
              <span className="mono">{confirmDiscard.originalTopic}</span>? It will not be replayed. This
              cannot be undone.
            </p>
          }
          confirmLabel="Discard"
          busyLabel="Discarding…"
          danger
          onConfirm={async () => {
            await discardDlqEntry(confirmDiscard.id);
            removeEntry(confirmDiscard.id);
          }}
          onClose={() => setConfirmDiscard(null)}
        />
      )}
    </section>
  );
}
