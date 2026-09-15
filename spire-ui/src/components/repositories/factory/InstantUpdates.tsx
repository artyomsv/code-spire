import { useState } from 'react';
import { createWebhookRepo, fetchWebhookRepos, type WebhookRepoSecret, type WebhookRepoView } from '../../../api';
import type { Repository } from '../repositoriesApi';
import type { WorkSource } from '../../work-items/workSourcesApi';
import { webhookPath } from '../../SettingsWebhookRepos';
import WebhookSecretReveal from '../WebhookSecretReveal';

interface Props {
  repository: Repository;
  source: WorkSource;
  hooks: WebhookRepoView[];
  /** The gateway owns webhooks and may be down while the orchestrator is not. */
  hooksUnavailable: boolean;
  onHooksChanged: (hooks: WebhookRepoView[]) => void;
}

/**
 * The issue webhook for one source. Scanning already finds every label within five minutes, and still
 * catches what a webhook misses during downtime, so the webhook is the fast path and never the only one.
 * The gateway refuses an issue webhook without its source, which is why this lives on the source.
 */
export default function InstantUpdates({ repository, source, hooks, hooksUnavailable, onHooksChanged }: Props) {
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [revealed, setRevealed] = useState<WebhookRepoSecret | null>(null);
  if (source.type === 'JIRA') return <p className="factory-note">Jira is polled. Changes are seen within five minutes; it has no instant updates here.</p>;
  const hook = hooks.find(value => value.repositoryId === repository.id && value.eventKind === 'ISSUE');

  async function enable() {
    setBusy(true); setError('');
    try {
      // A previous response may have been lost after the save committed; a second create would be refused.
      const current = await fetchWebhookRepos();
      if (current.some(value => value.repositoryId === repository.id && value.eventKind === 'ISSUE')) {
        onHooksChanged(current);
        setError('Instant updates were already turned on. If the secret was not copied, rotate it on the Details tab.');
        return;
      }
      const result = await createWebhookRepo({ providerType: source.type.toLowerCase(), forgeOrigin: repository.forgeOrigin, repositoryId: repository.id,
        eventKind: 'ISSUE', sourceId: source.id, scope: 'repo', target: source.scope, enabled: true });
      onHooksChanged([...current, result.repo]); setRevealed(result);
    } catch (failure) { setError(`Instant updates could not be turned on: ${String(failure)}. Scanning still finds new labels.`); }
    finally { setBusy(false); }
  }

  return <div className="factory-row">
    {hook
      ? <span className="factory-note"><span className={`chip ${hook.enabled ? 'ok' : 'no'}`}>{hook.enabled ? 'instant updates on' : 'instant updates paused'}</span>
        {' '}<span className="mono">{webhookPath(hook)}</span></span>
      : <span className="factory-note">Instant updates are off. New labels are seen within five minutes.</span>}
    {!hook && <button className="btn-ghost sm" type="button" disabled={busy || hooksUnavailable} onClick={() => void enable()}>
      Turn on instant updates</button>}
    {hooksUnavailable && !hook && <span className="prov-sub">Webhooks cannot be loaded right now.</span>}
    {error && <p className="prov-error" role="alert">{error}</p>}
    {revealed && <WebhookSecretReveal revealed={revealed} onDone={() => setRevealed(null)} />}
  </div>;
}
