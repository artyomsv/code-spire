import { useEffect, useState } from 'react';
import { KeyRound } from 'lucide-react';
import {
  addHarnessCredential, clearHarnessCredentialRejection, disableHarnessCredential,
  enableHarnessCredential, fetchHarnessCredentials, restHarnessCredential,
  type HarnessCredentialView,
} from '../api';
import HarnessSubscriptionSignIn from './HarnessSubscriptionSignIn';
import SettingField from './SettingField';
import SidePanel from './SidePanel';

/** The harness a subscription can pay for. One arm has one today, and the screen says so rather than
 * offering a choice with a single entry in it. */
const SUBSCRIPTION_HARNESS = 'codex';

/** The vendor kinds the pool accepts, as the API validates them. */
const TYPES = ['openai', 'anthropic', 'gemini'] as const;

/** What the row is doing right now, in the order that decides what an operator can act on. */
function state(member: HarnessCredentialView): { label: string; tone: string } {
  if (member.rejectedAt) return { label: 'Rejected', tone: 'chip danger' };
  if (member.rateLimitedUntil && new Date(member.rateLimitedUntil) > new Date()) return { label: 'Resting', tone: 'chip warn' };
  if (!member.enabled) return { label: 'Switched off', tone: 'chip' };
  // A seat whose account is unknown could be a second seat on one account, so no build uses it.
  if (member.authMode === 'SUBSCRIPTION' && member.identified === false) return { label: 'Sign in again', tone: 'chip warn' };
  if (member.authMode === 'SUBSCRIPTION') return { label: 'Ready · subscription', tone: 'chip ok' };
  return { label: 'Available', tone: 'chip ok' };
}

const when = (value: string | null) => (value ? new Date(value).toLocaleString() : '—');

/** Server refusals that have a sentence; anything else is shown as the server said it. */
function sentence(message: string): string {
  if (message.includes('subscription_account_taken'))
    return 'Another seat is already signed in to this account. Switch that one off first.';
  return message;
}

/**
 * The keys a factory run may call the model with (FR-F12, ADR-031).
 *
 * <p>The pool has existed since M1 and had no screen: members were added with `curl`, and the two
 * exhaustion states it keeps apart — a rate limit that lifts, and a rejection that does not — could
 * only be read from the database. A pool whose health is invisible is a pool that stops rotating
 * quietly, which is the failure it was built to prevent.
 *
 * <p>No response carries a key: the API has no field for one, so this screen cannot show one.
 */
export default function SettingsHarnessCredentials() {
  const [members, setMembers] = useState<HarnessCredentialView[] | null>(null);
  const [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [adding, setAdding] = useState(false), [busy, setBusy] = useState(false);
  const [signingIn, setSigningIn] = useState(false);
  const [form, setForm] = useState({ label: '', type: 'openai', baseUrl: '', apiKey: '' });
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    let live = true;
    fetchHarnessCredentials()
      .then(rows => { if (live) { setMembers(rows); setError(''); } })
      .catch(failure => { if (live) setError(String(failure instanceof Error ? failure.message : failure)); });
    return () => { live = false; };
  }, [refresh]);

  const reload = (message: string) => { setNotice(message); setRefresh(value => value + 1); };

  async function act(action: () => Promise<unknown>, message: string) {
    setBusy(true); setError('');
    try { await action(); reload(message); }
    catch (failure) { setError(sentence(String(failure instanceof Error ? failure.message : failure))); }
    finally { setBusy(false); }
  }

  async function save() {
    await act(() => addHarnessCredential({ ...form, label: form.label.trim(), baseUrl: form.baseUrl.trim() }),
      `Added ${form.label.trim()}. The pool will use it for the next run that needs a key.`);
    setAdding(false);
    setForm({ label: '', type: 'openai', baseUrl: '', apiKey: '' });
  }

  const complete = !!form.label.trim() && !!form.baseUrl.trim() && !!form.apiKey;
  return (
    <section className="content">
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title"><KeyRound size={15} className="an-title-icon" /> Harness credentials</h2>
          <div className="prov-actions">
            <button className="btn" type="button" disabled={busy || adding} onClick={() => { setNotice(''); setAdding(true); }}>
              Add an API key
            </button>
            {/* The other way to pay. A button rather than a second field on the key form: nothing is
                typed here, and putting the two side by side would suggest a file to paste. */}
            <button className="btn-ghost" type="button" disabled={busy || signingIn}
              onClick={() => { setNotice(''); setSigningIn(true); }}>
              Sign in with a Codex subscription
            </button>
          </div>
        </div>

        <p className="prov-note">
          The keys a factory run calls the model with. They are separate from the reviewer's own key on
          purpose: an agent runs a model on an untrusted ticket at full shell access, and one leaked key
          must not stop reviews as well. A run picks the member rested longest, so several keys spread the
          load rather than burning one window.
        </p>
        <p className="prov-note">
          A signed-in Codex subscription pays for builds whose setup says Pay with: subscription, one build
          at a time. A build on it costs nothing per token; its token counts are still recorded.
        </p>

        {signingIn && (
          <SidePanel title="Sign in with a Codex subscription" busy={false} onClose={() => setSigningIn(false)}
            actions={<button className="btn-ghost" type="button" onClick={() => setSigningIn(false)}>Close</button>}>
            <HarnessSubscriptionSignIn harness={SUBSCRIPTION_HARNESS} done={label => {
              setSigningIn(false);
              // Says what actually happened. "Runs can now be paid by the subscription" was false:
              // nothing selects, injects or bills one yet, and the credential is deliberately
              // unreachable by every run until all three exist.
              reload(`Saved ${label}. Builds whose setup pays with a subscription can use it now.`);
            }} />
          </SidePanel>
        )}

        {notice && <p className="prov-note" role="status">{notice}</p>}
        {error && <p className="prov-error" role="alert">{error}</p>}

        {members !== null && members.length === 0 && (
          <div className="wh-empty" role="status">
            <div className="wh-empty-icon"><KeyRound size={20} /></div>
            <p className="an-empty-title">No key yet</p>
            <p className="prov-note">Until one is added, every factory run is refused before it starts.</p>
          </div>
        )}

        {members !== null && members.length > 0 && (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Label</th><th>Kind</th><th>Endpoint</th><th>State</th><th>Last used</th><th className="cell-r">Actions</th>
              </tr>
            </thead>
            <tbody>
              {members.map(member => {
                const shown = state(member);
                return (
                  <tr key={member.id}>
                    <td>{member.label}</td>
                    <td>{member.type}</td>
                    <td className="mono">{member.baseUrl}</td>
                    <td>
                      <span className={shown.tone}>{shown.label}</span>
                      {member.rateLimitedUntil && <span className="prov-sub"> until {when(member.rateLimitedUntil)}</span>}
                      {member.rejectedAt && <span className="prov-sub"> at {when(member.rejectedAt)}</span>}
                    </td>
                    <td>{when(member.lastUsedAt)}</td>
                    <td className="cell-r">
                      {member.rejectedAt && (
                        <button className="btn-ghost sm" type="button" disabled={busy}
                          onClick={() => void act(() => clearHarnessCredentialRejection(member.id),
                            `${member.label} may be tried again. Replace the key at the vendor first if it was revoked.`)}>
                          Try again
                        </button>
                      )}
                      {member.rateLimitedUntil && !member.rejectedAt && (
                        <button className="btn-ghost sm" type="button" disabled={busy}
                          onClick={() => void act(() => restHarnessCredential(member.id), `${member.label} is resting.`)}>
                          Rest longer
                        </button>
                      )}
                      {member.enabled ? (
                        <button className="btn-ghost sm danger" type="button" disabled={busy}
                          onClick={() => void act(() => disableHarnessCredential(member.id), `${member.label} is switched off.`)}>
                          Switch off
                        </button>
                      ) : (
                        <button className="btn-ghost sm" type="button" disabled={busy}
                          onClick={() => void act(() => enableHarnessCredential(member.id), `${member.label} is back in the pool.`)}>
                          Switch on
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}

        {members === null && !error && <p className="prov-note" role="status" aria-busy="true">Loading the credential pool…</p>}
      </div>

      {adding && (
        <SidePanel title="Add an API key" busy={busy} onClose={() => setAdding(false)}
          actions={<>
            <button className="btn" type="button" disabled={busy || !complete} onClick={() => void save()}>
              {busy ? 'Adding…' : 'Add the key'}
            </button>
            <button className="btn-ghost" type="button" disabled={busy} onClick={() => setAdding(false)}>Cancel</button>
          </>}>
          <SettingField label="Label" scope="harness credential" hint="Required. Your own name for it, so a rejection names something you can find in the vendor's console.">
            <input aria-label="Label" value={form.label} onChange={event => setForm({ ...form, label: event.target.value })} />
          </SettingField>
          <SettingField label="Kind" scope="harness credential" hint="Required. The vendor this key belongs to.">
            <select aria-label="Kind" value={form.type} onChange={event => setForm({ ...form, type: event.target.value })}>
              {TYPES.map(type => <option key={type} value={type}>{type}</option>)}
            </select>
          </SettingField>
          <SettingField label="Endpoint" scope="harness credential" hint="Required. The API base URL, for example https://api.openai.com/v1.">
            <input aria-label="Endpoint" value={form.baseUrl} placeholder="https://api.openai.com/v1"
              onChange={event => setForm({ ...form, baseUrl: event.target.value })} />
          </SettingField>
          <SettingField label="Key" scope="harness credential" hint="Required. Stored encrypted and never shown again — not here, not in a log, not in a run's environment dump.">
            <input aria-label="Key" type="password" autoComplete="off" value={form.apiKey}
              onChange={event => setForm({ ...form, apiKey: event.target.value })} />
          </SettingField>
          {error && <p className="prov-error" role="alert">{error}</p>}
        </SidePanel>
      )}
    </section>
  );
}
