import { useState } from 'react';
import { createWebhookRepo, fetchWebhookRepos, updateWebhookRepo, rotateWebhookSecret, verifyRepo,
  type WebhookRepoView, type WebhookRepoSecret, type WebhookEventKind } from '../../api';
import type { Repository } from './repositoriesApi';
import ActorPicker from '../ActorPicker';
import RepositoryAccountsCell from './RepositoryAccountsCell';
import { CopyableValue } from '../../render';
import WebhookSecretReveal from './WebhookSecretReveal';
import { webhookPath } from '../SettingsWebhookRepos';

interface Props {
  repository: Repository;
  hooks: WebhookRepoView[];
  onHooksChanged: (hooks: WebhookRepoView[]) => void;
}
const kinds: WebhookEventKind[] = ['REVIEWER', 'FACTORY', 'ISSUE'];

/** The body of the repository side panel. The panel supplies the name, coordinates and actions. */
export default function RepositoryDetail({ repository, hooks, onHooksChanged }: Props) {
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

  return <div className="panel-detail">
    <section aria-label="Workspace" className="prov-note"><h4>Workspace</h4><p className="mono">{repository.workspace}</p><p className="prov-sub">{repository.scmType} · {repository.forgeOrigin}</p></section>
    <section aria-label="Selected accounts" className="prov-note"><h4>Selected accounts</h4>
      <RepositoryAccountsCell repository={repository} />
      {repository.reviewer && <div className="prov-actions"><button className="btn-ghost" disabled={busy} onClick={() => void verify()}>Verify reviewer access</button></div>}
      {verification && <p role="status">{verification}</p>}
    </section>
    <section aria-label="Webhooks"><div className="prov-head"><h4 className="prov-title">Webhooks</h4></div>
      <p className="prov-note">Hooks are optional. A registered repository can be used for manual reviews and runs.</p>
      <div className="prov-scroll"><table className="prov-table">
      <thead><tr><th>Kind</th><th>Webhook path</th><th>State</th><th>Actions</th></tr></thead>
      {kinds.map(kind => {
        const hook = hooks.find(h => h.repositoryId === repository.id && h.eventKind === kind)
          ?? hooks.find(h => isLegacyCandidate(h, kind) && h.forgeOrigin === repository.forgeOrigin);
        return <tbody key={kind} role="group" aria-label={`${kind} webhook`}><tr>
          <td className="mono nowrap">{kind}</td>
          <td>{hook ? <div className="wh-url"><CopyableValue text={webhookPath(hook)} mono copyTitle="Copy the webhook path" /></div>
            : <span className="prov-sub">{kind === 'ISSUE' ? 'Turn on instant updates in the Factory tab.' : 'Not configured'}</span>}</td>
          <td>{hook && <div className="chips"><span className={`chip ${hook.enabled ? 'on' : ''}`}>{hook.enabled ? 'Enabled' : 'Disabled'}</span></div>}</td>
          <td><div className="prov-actions">{hook ? <>
            {!hook.repositoryId && <button className="btn-ghost" disabled={busy} onClick={() => void link(hook)} aria-label={`Link existing ${kind} webhook`}>Link existing</button>}
            <button className="btn-ghost" disabled={busy} onClick={() => void toggle(hook)} aria-label={`${hook.enabled ? 'Disable' : 'Enable'} ${kind} webhook`}>{hook.enabled ? 'Disable' : 'Enable'}</button>
            <button className="btn-ghost" disabled={busy} onClick={() => void rotate(hook)} aria-label={`Rotate ${kind} secret`}>Rotate secret</button>
          </> : kind !== 'ISSUE' && <button className="btn-ghost" disabled={busy} onClick={() => void create(kind)} aria-label={`Create ${kind} webhook`}>Create webhook</button>}</div></td>
        </tr></tbody>;
      })}
      </table></div>
    </section>
    <div className="prov-note">{repository.reviewer ? <ActorPicker accountId={repository.reviewer.id} accountType={repository.scmType} repositoryId={repository.id} />
      : <p>Select a reviewer account to edit fix overrides.</p>}</div>
    {error && <p className="prov-note prov-error" role="alert">{error}</p>}
    {revealed && <WebhookSecretReveal revealed={revealed} onDone={() => setRevealed(null)} />}
  </div>;
}
