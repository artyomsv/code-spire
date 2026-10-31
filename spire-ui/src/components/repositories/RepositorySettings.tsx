import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchLlmProviders, llmProvidersKey, type ProviderView } from '../../api';
import { saveRepository, type Repository, type RepositoryInput } from './repositoriesApi';
import RepositoryAccountSelects from './RepositoryAccountSelects';
import RepositorySwitches from './RepositorySwitches';
import RepositoryReviewProvider from './RepositoryReviewProvider';

interface Props {
  repository: Repository;
  providers: ProviderView[];
  /** The tab is not showing. It stays mounted so an unsaved change survives a look at another tab. */
  hidden: boolean;
  onSaved: (repository: Repository) => void;
  onState: (state: SettingsState) => void;
}

/** What the owning panel needs: a dirty tab is marked, and a pending save locks the whole panel. */
export interface SettingsState {
  dirty: boolean;
  busy: boolean;
}

export const SETTLED: SettingsState = { dirty: false, busy: false };

function inputOf(repository: Repository): RepositoryInput {
  return {
    scmType: repository.scmType, forgeOrigin: repository.forgeOrigin, workspace: repository.workspace, slug: repository.slug,
    enabled: repository.enabled, reviewerAccountId: repository.reviewer?.id ?? null, factoryAccountId: repository.factory?.id ?? null,
    reviewerMayApprove: repository.reviewerMayApprove, reviewLlmProviderId: repository.reviewLlmProviderId ?? null,
  };
}

function changed(fields: RepositoryInput, saved: RepositoryInput): boolean {
  return (Object.keys(saved) as (keyof RepositoryInput)[]).some(key => fields[key] !== saved[key]);
}

/**
 * The Settings tab of the repository panel: what an operator may change about a registered repository.
 * Its identity — forge, origin, workspace, slug — is shown as text: a different one is a different
 * repository, registered anew. It replaced a second side panel opened over this one (operator, 2026-10-16).
 */
export default function RepositorySettings({ repository, providers, hidden, onSaved, onState }: Props) {
  const [saved, setSaved] = useState(repository);
  const [fields, setFields] = useState(() => inputOf(repository));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [justSaved, setJustSaved] = useState(false);
  const llmProviders = useQuery({ queryKey: llmProvidersKey, queryFn: () => fetchLlmProviders() });
  const dirty = changed(fields, inputOf(saved));
  // The panel owns the lock: an input still editable while a save is in flight is overwritten by its answer.
  useEffect(() => { onState({ dirty, busy }); }, [dirty, busy, onState]);
  useEffect(() => () => onState(SETTLED), [onState]);

  const patch = (next: Partial<RepositoryInput>) => { setFields(previous => ({ ...previous, ...next })); setJustSaved(false); };
  async function submit() {
    setBusy(true); setError(null);
    try {
      const result = await saveRepository(fields, saved);
      setSaved(result); setFields(inputOf(result)); setJustSaved(true); onSaved(result);
    } catch (failure) { setError(failure instanceof Error ? failure.message : String(failure)); }
    finally { setBusy(false); }
  }
  function discard() { setFields(inputOf(saved)); setError(null); }

  return <div className="panel-section" hidden={hidden}>
    <section aria-label="Accounts" className="settings-group"><h4>Accounts</h4>
      <RepositoryAccountSelects fields={fields} providers={providers} onChange={patch} /></section>
    <section aria-label="Reviewing" className="settings-group"><h4>Reviewing</h4>
      <RepositorySwitches fields={fields} onChange={patch} />
      <RepositoryReviewProvider value={fields.reviewLlmProviderId} providers={llmProviders.data ?? []}
        onChange={reviewLlmProviderId => patch({ reviewLlmProviderId })} /></section>
    <section aria-label="Identity" className="settings-group"><h4>Identity</h4>
      <dl className="repo-identity">
        <div><dt>Forge kind</dt><dd>{saved.scmType}</dd></div>
        <div><dt>Forge origin</dt><dd>{saved.forgeOrigin}</dd></div>
        <div><dt>Workspace</dt><dd>{saved.workspace}</dd></div>
        <div><dt>Repository slug</dt><dd>{saved.slug}</dd></div>
      </dl>
      <p className="prov-sub">Set at registration and never edited. A different one is a different repository: register it.</p></section>
    {error && <p className="prov-error" role="alert">{error}</p>}
    <div className="settings-actions">
      <button className="btn" type="button" disabled={!dirty || busy} onClick={() => void submit()}>{busy ? 'Saving…' : 'Save changes'}</button>
      <button className="btn-ghost" type="button" disabled={!dirty || busy} onClick={discard}>Discard</button>
      {dirty ? <span className="settings-state warn">Unsaved changes</span> : justSaved && <span className="settings-state good">Saved</span>}
    </div>
  </div>;
}
