import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  checkProvider,
  fetchProviders,
  providersKey,
  type ProviderView,
} from '../api';
import { invalidateLive, mergeLive } from '../liveCache';
import { useEachRead } from '../hooks/useEachRead';
import AccountsTable, { type Conn } from './AccountsTable';
import AccountsTabs from './AccountsTabs';
import ProviderFormModal, { DeleteConfirmModal } from './ProviderFormModal';
import Tooltip from './Tooltip';
import { useEditDeepLink } from '../hooks/useEditDeepLink';

export { conversationLabel } from './ProviderFormModal';

/** One array while nothing is read, so the deep link's effect does not re-run on every render. */
const NO_PROVIDERS: ProviderView[] = [];

export default function SettingsProviders() {
  const client = useQueryClient();
  // Shared with the repositories page and the context form. A re-read that fails after a success keeps the list.
  const read = useQuery({ queryKey: providersKey, queryFn: () => fetchProviders() });
  const providers = read.data ?? NO_PROVIDERS;
  const loading = read.data === undefined && !read.error;
  const error = read.error ? (read.error instanceof Error ? read.error.message : String(read.error)) : null;
  const [conns, setConns] = useState<Record<string, Conn>>({});
  // null = form closed; a ProviderView = editing; 'new' = adding.
  const [form, setForm] = useState<'new' | ProviderView | null>(null);
  // An attention row names one provider; land the operator on it, not just on this page.
  useEditDeepLink(providers, setForm);
  const [confirmDelete, setConfirmDelete] = useState<ProviderView | null>(null);

  async function checkOne(id: string) {
    setConns((prev) => ({ ...prev, [id]: { state: 'checking' } }));
    try {
      const r = await checkProvider(id);
      // Without cancelling a read: one a save started must not be lost to a check that answered late. The server stores
      // the scopes too, so a read that lands afterwards carries them anyway.
      if (r.scopesCheckedAt !== undefined) mergeLive<ProviderView[] | undefined>(client, providersKey, (rows) => rows?.map((row) => row.id === id
        ? { ...row, reportedScopes: r.reportedScopes, scopesCheckedAt: r.scopesCheckedAt } : row));
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

  // After each completed read, and never for the scope write above, which would check again and again.
  useEachRead<ProviderView[]>(providersKey, (list) => {
    // A live result belongs to the account as it was when it answered. Disabling an account that
    // had just checked green used to leave that green behind — the row said OK for a credential
    // nothing was using any more. Anything no longer enabled goes back to its stored standing.
    const live = new Set(list.filter((x) => x.enabled).map((x) => x.id));
    setConns((prev) => Object.fromEntries(Object.entries(prev).filter(([id]) => live.has(id))));
    // Check connectivity once on load, but ONLY for enabled providers — a
    // disabled provider is intentionally inactive, so contacting the SCM for
    // it is wasteful and confusing (it may hold a deliberately stale/revoked
    // token). A disabled row shows what the registry stored and can be re-checked on demand.
    list.filter((p) => p.enabled).forEach((p) => void checkOne(p.id));
  });
  const load = () => void invalidateLive(client, providersKey);

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
        {error && read.data !== undefined && <p className="prov-note prov-error" role="alert">{error}</p>}
        {error && read.data === undefined ? (
          <div style={{ padding: '26px 18px', color: 'var(--crit)', fontSize: 13 }}>{error}</div>
        ) : loading ? (
          <div style={{ padding: '26px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : providers.length === 0 ? (
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
            <AccountsTable
              providers={providers}
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
