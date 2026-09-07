import { useEffect, useState } from 'react';
import {
  checkProvider,
  fetchContextProviders,
  fetchProviders,
  type ContextProviderView,
  type ProviderView,
} from '../api';
import AccountsTable, { type Conn } from './AccountsTable';
import AccountsTabs from './AccountsTabs';
import ProviderFormModal, { DeleteConfirmModal } from './ProviderFormModal';
import Tooltip from './Tooltip';
import { useEditDeepLink } from '../hooks/useEditDeepLink';

export { conversationLabel } from './ProviderFormModal';

export default function SettingsProviders() {
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [conns, setConns] = useState<Record<string, Conn>>({});
  // Tracker and knowledge accounts, listed read-only beneath the forge rows.
  const [trackers, setTrackers] = useState<ContextProviderView[]>([]);
  const [trackerError, setTrackerError] = useState<string | null>(null);

  // null = form closed; a ProviderView = editing; 'new' = adding.
  const [form, setForm] = useState<'new' | ProviderView | null>(null);
  // An attention row names one provider; land the operator on it, not just on this page.
  useEditDeepLink(providers, setForm);
  const [confirmDelete, setConfirmDelete] = useState<ProviderView | null>(null);

  async function checkOne(id: string) {
    setConns((prev) => ({ ...prev, [id]: { state: 'checking' } }));
    try {
      const r = await checkProvider(id);
      setConns((prev) => ({
        ...prev,
        [id]: r.ok ? { state: 'ok', account: r.account } : { state: 'fail', detail: r.detail },
      }));
    } catch (err) {
      setConns((prev) => ({
        ...prev,
        [id]: { state: 'fail', detail: err instanceof Error ? err.message : String(err) },
      }));
    }
  }

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const list = await fetchProviders();
      setProviders(list);
      // Check connectivity once on load, but ONLY for enabled providers — a
      // disabled provider is intentionally inactive, so contacting the SCM for
      // it is wasteful and confusing (it may hold a deliberately stale/revoked
      // token). Disabled rows render an idle cell and can be re-checked on demand.
      list.filter((p) => p.enabled).forEach((p) => void checkOne(p.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }

    // Tracker and knowledge accounts are listed read-only. Loaded separately so a failure there
    // cannot take the forge list with it: the forge list is the one a review depends on.
    try {
      setTrackers(await fetchContextProviders());
      setTrackerError(null);
    } catch (err) {
      setTrackerError(err instanceof Error ? err.message : String(err));
    }
  }

  useEffect(() => {
    void load();
  }, []);

  return (
    <section className="content">
      <AccountsTabs active="machine" />

      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">Accounts</h2>
          <Tooltip label="Add account">
            <button className="iconbtn" onClick={() => setForm('new')} aria-label="Add account">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none">
                <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
            </button>
          </Tooltip>
        </div>
        {error ? (
          <div style={{ padding: '26px 18px', color: 'var(--crit)', fontSize: 13 }}>{error}</div>
        ) : loading && providers.length === 0 ? (
          <div style={{ padding: '26px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : providers.length === 0 && trackers.length === 0 ? (
          <div className="prov-empty">
            <span>No machine accounts yet. Add a reviewer account to start reviewing.</span>
            <button className="btn" onClick={() => setForm('new')}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
                <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
              </svg>
              Add account
            </button>
          </div>
        ) : (
          <>
            {trackerError && <p className="prov-note">Tracker accounts could not be loaded: {trackerError}</p>}
            <AccountsTable
              providers={providers}
              trackers={trackers}
              conns={conns}
              onRecheck={(id) => void checkOne(id)}
              onEdit={setForm}
              onDelete={setConfirmDelete}
            />
          </>
        )}
      </div>

      {form && (
        <ProviderFormModal
          initial={form === 'new' ? null : form}
          onClose={() => setForm(null)}
          onSaved={() => {
            setForm(null);
            void load();
          }}
        />
      )}

      {confirmDelete && (
        <DeleteConfirmModal
          provider={confirmDelete}
          onClose={() => setConfirmDelete(null)}
          onDeleted={() => {
            setConfirmDelete(null);
            void load();
          }}
        />
      )}
    </section>
  );
}
