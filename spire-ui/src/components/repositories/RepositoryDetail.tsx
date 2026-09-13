import { useState } from 'react';
import { createWebhookRepo, fetchWebhookRepos, updateWebhookRepo, rotateWebhookSecret, verifyRepo,
  type WebhookRepoView, type WebhookRepoSecret, type WebhookEventKind } from '../../api';
import type { Repository, RepositoryAccount } from './repositoriesApi';

interface Props {
  repository: Repository;
  hooks: WebhookRepoView[];
  onHooksChanged: (hooks: WebhookRepoView[]) => void;
  onEdit: () => void;
}
const kinds: WebhookEventKind[] = ['REVIEWER', 'FACTORY', 'ISSUE'];

function account(account: RepositoryAccount | null): string {
  return account ? `${account.name}${account.handle ? ` (@${account.handle})` : ''} · ${account.state}` : 'No account selected';
}

export default function RepositoryDetail({ repository, hooks, onHooksChanged, onEdit }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revealed, setRevealed] = useState<WebhookRepoSecret | null>(null);
  const [verification, setVerification] = useState<string | null>(null);
  const isLegacyCandidate = (hook: WebhookRepoView, kind: WebhookEventKind) => !hook.repositoryId
    && hook.providerType === repository.scmType && hook.scope === 'repo' && hook.eventKind === kind
    && hook.target === `${repository.workspace}/${repository.slug}`
    && (!hook.forgeOrigin || hook.forgeOrigin === repository.forgeOrigin);

  async function verify() {
    if (!repository.reviewer) return;
    setBusy(true); setVerification(null);
    try {
      const result = await verifyRepo(repository.reviewer.id, `${repository.workspace}/${repository.slug}`);
      setVerification(result.ok ? 'Repository found with the selected reviewer account.' : result.detail ?? 'Repository access could not be verified.');
    } catch (failure) { setVerification(String(failure)); }
    finally { setBusy(false); }
  }

  async function create(kind: WebhookEventKind) {
    setBusy(true); setError(null);
    try {
      // A previous response may have been lost after the save committed. Read before retrying.
      const current = await fetchWebhookRepos();
      const existing = current.find(h => h.repositoryId === repository.id && h.eventKind === kind);
      const legacy = current.find(h => isLegacyCandidate(h, kind));
      if (existing) {
        onHooksChanged(current);
        setError('This webhook is already saved. If its secret was not copied, rotate it explicitly.');
      } else if (legacy) {
        onHooksChanged(current);
        setError('An existing legacy webhook needs its forge origin confirmed and repository linked. Repair it before creating another webhook.');
      } else {
        const result = await createWebhookRepo({ providerType: repository.scmType, forgeOrigin: repository.forgeOrigin,
          repositoryId: repository.id, eventKind: kind, scope: 'repo', target: `${repository.workspace}/${repository.slug}`, enabled: true });
        onHooksChanged([...current, result.repo]); setRevealed(result);
      }
    } catch (failure) { setError(`Repository remains saved. Webhook could not be created: ${String(failure)}. Retry when ready.`); }
    finally { setBusy(false); }
  }

  async function link(hook: WebhookRepoView) {
    setBusy(true); setError(null);
    try {
      const changed = await updateWebhookRepo(hook.id, { providerType: hook.providerType, scope: hook.scope,
        target: hook.target, enabled: hook.enabled, repositoryId: repository.id, forgeOrigin: repository.forgeOrigin,
        eventKind: hook.eventKind });
      onHooksChanged(hooks.map(h => h.id === changed.id ? changed : h));
    } catch (failure) { setError(String(failure)); }
    finally { setBusy(false); }
  }

  async function toggle(hook: WebhookRepoView) {
    setBusy(true); setError(null);
    try {
      const changed = await updateWebhookRepo(hook.id, { providerType: hook.providerType, scope: hook.scope,
        target: hook.target, enabled: !hook.enabled });
      onHooksChanged(hooks.map(h => h.id === changed.id ? changed : h));
    } catch (failure) { setError(String(failure)); }
    finally { setBusy(false); }
  }

  async function rotate(hook: WebhookRepoView) {
    if (!window.confirm('Rotate this secret? Update the forge webhook with the new secret afterward.')) return;
    setBusy(true); setError(null);
    try { setRevealed(await rotateWebhookSecret(hook.id)); }
    catch (failure) { setError(String(failure)); }
    finally { setBusy(false); }
  }

  return <article className="card" style={{ padding: 18, marginTop: 16 }}>
    <h3>{repository.slug}</h3>
    <section aria-label="Workspace"><h4>Workspace</h4><p>{repository.workspace}</p><p>{repository.scmType} · {repository.forgeOrigin}</p></section>
    <section aria-label="Selected accounts"><h4>Selected accounts</h4>
      <p>Reviewer: {account(repository.reviewer)}</p><p>Factory: {account(repository.factory)}</p>
      <button className="btn" onClick={onEdit}>Edit repository and accounts</button>
      {repository.reviewer && <button className="btn" disabled={busy} onClick={() => void verify()}>Verify reviewer access</button>}
      {verification && <p role="status">{verification}</p>}
    </section>
    <section aria-label="Webhooks"><h4>Webhooks</h4>
      <p>Hooks are optional. A registered repository can be used for manual reviews and runs.</p>
      {kinds.map(kind => {
        const hook = hooks.find(h => h.repositoryId === repository.id && h.eventKind === kind)
          ?? hooks.find(h => isLegacyCandidate(h, kind) && h.forgeOrigin === repository.forgeOrigin);
        return <div key={kind} role="group" aria-label={`${kind} webhook`} style={{ marginBottom: 12 }}>
          <strong>{kind}</strong>{hook ? <>
            <span> · {hook.enabled ? 'Enabled' : 'Disabled'}</span>
            <p><code>/webhooks/{hook.providerType}/{hook.webhookKey}</code></p>
            {!hook.repositoryId && <button className="btn" disabled={busy} onClick={() => void link(hook)}>Link existing {kind} webhook</button>}
            <button className="btn" disabled={busy} onClick={() => void toggle(hook)}>{hook.enabled ? 'Disable' : 'Enable'} {kind} webhook</button>
            <button className="btn" disabled={busy} onClick={() => void rotate(hook)}>Rotate {kind} secret</button>
          </> : kind === 'ISSUE' ? <p>Configure a work source to enable issue events.</p>
            : <button className="btn" disabled={busy} onClick={() => void create(kind)}>Create {kind} webhook</button>}
        </div>;
      })}
    </section>
    {error && <p role="alert">{error}</p>}
    {revealed && <div role="dialog" aria-label="Webhook secret">
      <p>Copy this secret now. It is shown once; store it in the forge webhook settings.</p>
      <code>{revealed.secret}</code><p><code>/webhooks/{revealed.repo.providerType}/{revealed.repo.webhookKey}</code></p>
      <button className="btn" onClick={() => setRevealed(null)}>Done</button>
    </div>}
  </article>;
}
