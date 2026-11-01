import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchLlmProviders, llmProvidersKey, type ProviderView } from '../../api';
import { saveRepository, type Repository, type RepositoryInput, type ReviewHarness } from './repositoriesApi';
import RepositoryAccountSelects from './RepositoryAccountSelects';
import RepositorySwitches from './RepositorySwitches';
import RepositoryReviewProvider, { HARNESS_CHOICE } from './RepositoryReviewProvider';
import ReviewHarnessFields from './ReviewHarnessFields';
import { buildDefaults, buildDefaultsKey, type BuildDefaults } from './factory/buildDefaultsApi';

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
    reviewHarness: repository.reviewHarness ?? null,
  };
}

/** A harness review starts from the build setup's coordinates when the repository has one, and blank otherwise. */
function harnessFrom(defaults: BuildDefaults | undefined): ReviewHarness {
  return { harness: defaults?.harness ?? '', model: defaults?.model ?? '', effort: defaults?.effort ?? null, payWith: defaults?.payWith ?? 'API_KEY' };
}

function sameHarness(a: ReviewHarness | null, b: ReviewHarness | null): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  return a.harness === b.harness && a.model === b.model && (a.effort ?? null) === (b.effort ?? null) && a.payWith === b.payWith;
}

function changed(fields: RepositoryInput, saved: RepositoryInput): boolean {
  return (Object.keys(saved) as (keyof RepositoryInput)[])
    .some(key => (key === 'reviewHarness' ? !sameHarness(fields.reviewHarness, saved.reviewHarness) : fields[key] !== saved[key]));
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
  const onHarness = fields.reviewHarness !== null;
  // Read only while a harness is chosen; a copy the Factory tab already read is used at once.
  const defaultsRead = useQuery({ queryKey: buildDefaultsKey(repository.id), queryFn: () => buildDefaults(repository.id), enabled: onHarness });
  const dirty = changed(fields, inputOf(saved));
  const harnessIncomplete = onHarness && (!fields.reviewHarness?.harness || !fields.reviewHarness?.model);
  // The panel owns the lock: an input still editable while a save is in flight is overwritten by its answer.
  useEffect(() => { onState({ dirty, busy }); }, [dirty, busy, onState]);
  useEffect(() => () => onState(SETTLED), [onState]);
  // A harness chosen before the build setup was read starts from it once it arrives, unless something was picked already.
  useEffect(() => {
    const start = harnessFrom(defaultsRead.data);
    if (!start.harness) return;
    setFields(previous => (previous.reviewHarness && !previous.reviewHarness.harness && !previous.reviewHarness.model
      ? { ...previous, reviewHarness: start } : previous));
  }, [defaultsRead.data]);

  const patch = (next: Partial<RepositoryInput>) => { setFields(previous => ({ ...previous, ...next })); setJustSaved(false); };
  const patchHarness = (update: (previous: ReviewHarness) => ReviewHarness) => {
    setFields(previous => (previous.reviewHarness ? { ...previous, reviewHarness: update(previous.reviewHarness) } : previous));
    setJustSaved(false);
  };
  // One "Review with" choice: a harness clears the provider, and a provider or the default clears the harness.
  function chooseReview(choice: string | null) {
    if (choice === HARNESS_CHOICE) {
      patch({ reviewLlmProviderId: null, reviewHarness: fields.reviewHarness ?? harnessFrom(defaultsRead.data) });
      return;
    }
    patch({ reviewLlmProviderId: choice, reviewHarness: null });
  }
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
      <RepositoryReviewProvider value={fields.reviewLlmProviderId} harness={onHarness} providers={llmProviders.data}
        failure={llmProviders.error ? (llmProviders.error instanceof Error ? llmProviders.error.message : String(llmProviders.error)) : null}
        onRetry={() => void llmProviders.refetch()}
        onChange={chooseReview} />
      {fields.reviewHarness && <ReviewHarnessFields repositoryId={repository.id} value={fields.reviewHarness} onChange={patchHarness} />}
      {harnessIncomplete && <p className="factory-note" role="status">To save, choose a harness and a model.</p>}</section>
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
      <button className="btn" type="button" disabled={!dirty || busy || harnessIncomplete} onClick={() => void submit()}>{busy ? 'Saving…' : 'Save changes'}</button>
      <button className="btn-ghost" type="button" disabled={!dirty || busy} onClick={discard}>Discard</button>
      {dirty ? <span className="settings-state warn">Unsaved changes</span> : justSaved && <span className="settings-state good">Saved</span>}
    </div>
  </div>;
}
