import { useEffect, useRef, useState } from 'react';
import { fetchLlmModels, type LlmModelView, type WorkItemDetail } from '../../api';
import { canAdminister } from '../../auth';
import { useMe } from '../../hooks/useMe';
import { branchHead, preparationOptions, resolveArtifact, registerPreparation, type ArtifactReference } from './workPreparationApi';
import { buildDefaults } from '../repositories/factory/buildDefaultsApi';
import { TOKEN_TYPE_LABEL, unpricedTypesFor } from '../../llmPricing';

/**
 * What this model cannot price of what the chosen harness reports; empty means a run may start.
 *
 * <p>With NO harness chosen the question has no answer yet, so nothing is judged: a missing entry for a
 * real harness name means "assume it reports everything", but an empty selection is not a harness.
 */
function unpriced(model: LlmModelView, harness: string, reported: Record<string, string[]>) {
  return harness ? unpricedTypesFor(model, reported[harness]) : [];
}

const missingLabel = (types: string[]) => types.map(type => TOKEN_TYPE_LABEL[type as keyof typeof TOKEN_TYPE_LABEL]).join(', ');

export default function WorkItemPreparation({ item, changed }: { item: WorkItemDetail; changed: () => void }) {
  const { me } = useMe();
  const active = useRef(true), sequence = useRef(0);
  useEffect(() => { active.current = true; return () => { active.current = false; sequence.current++; }; }, []);
  const [form, setForm] = useState({ specification: item.preparation?.specification.location.issueKey ?? '', plan: item.preparation?.plan.location.issueKey ?? '',
    baseBranch: item.preparation?.baseBranch ?? '', baseCommit: item.preparation?.baseCommit ?? '', harness: item.preparation?.harness ?? '', model: item.preparation?.model ?? '' });
  const [references, setReferences] = useState<{ specification: ArtifactReference; plan: ArtifactReference | null } | null>(null);
  /** Which coordinates the operator has changed, so a late default cannot undo a deliberate clear. */
  const touched = useRef(new Set<string>());
  const [busy, setBusy] = useState<'checking' | 'saving' | 'head' | null>(null), [error, setError] = useState('');
  // What this deployment can actually run. A harness with no agent image, and a model with no price,
  // are both refused at dispatch — after the operator has typed them and waited.
  const [choices, setChoices] = useState<{ harnesses: string[]; models: LlmModelView[]; reportedTypes: Record<string, string[]> }>(
    { harnesses: [], models: [], reportedTypes: {} });
  useEffect(() => {
    let live = true;
    Promise.all([preparationOptions(item.id), fetchLlmModels()])
      // A wire answer of the wrong shape degrades to "nothing offered" rather than blanking the page.
      .then(([options, models]) => { if (live) setChoices({ harnesses: options.harnesses ?? [],
        models: (models ?? []).filter(model => model.enabled), reportedTypes: options.reportedTypes ?? {} }); })
      .catch(() => { /* the selects fall back to what is already registered; registration still refuses an unrunnable pair */ });
    return () => { live = false; };
  }, [item.id]);
  // The repository's saved build setup, on its OWN request: it is optional, and a slow answer for it
  // must not hold back the harness and model this deployment can run.
  useEffect(() => {
    if (item.preparation) return; // what is registered is what a gate binds; never overwrite it
    let live = true;
    buildDefaults(item.repositoryId)
      // A coordinate the operator has touched is theirs, including one they deliberately cleared.
      .then(defaults => { if (live) setForm(previous => ({ ...previous,
        baseBranch: touched.current.has('baseBranch') ? previous.baseBranch : previous.baseBranch || defaults.baseBranch || '',
        harness: touched.current.has('harness') ? previous.harness : previous.harness || defaults.harness || '',
        model: touched.current.has('model') ? previous.model : previous.model || defaults.model || '' })); })
      .catch(() => { /* a repository without a saved setup prepares by hand, as before */ });
    return () => { live = false; };
  }, [item.repositoryId, item.preparation]);
  async function readHead() {
    sequence.current++; setBusy('head'); setError('');
    try { const head = await branchHead(item.id, form.baseBranch); if (active.current) setForm(previous => ({ ...previous, baseBranch: head.branch, baseCommit: head.commit })); }
    catch (failure) { if (active.current) setError(String(failure)); }
    finally { if (active.current) setBusy(null); }
  }
  function edit(key: keyof typeof form, value: string) { touched.current.add(key); sequence.current++; setForm(previous => ({ ...previous, [key]: value })); setReferences(null); setBusy(null); }
  async function inspect(onlySpecification = false) {
    const request = ++sequence.current; setBusy('checking'); setError(''); setReferences(null);
    try {
      const [specification, plan] = await Promise.all([resolveArtifact(item.id, form.specification), onlySpecification ? Promise.resolve(null) : resolveArtifact(item.id, form.plan)]);
      if (request === sequence.current) setReferences({ specification, plan });
    } catch (failure) { if (request === sequence.current) setError(String(failure)); }
    finally { if (request === sequence.current) setBusy(null); }
  }
  async function register() {
    if (!references?.plan) return;
    setBusy('saving'); setError('');
    try {
      await registerPreparation(item.id, { expectedRevision: item.revision, specification: references.specification.artifact, plan: references.plan.artifact,
        baseBranch: form.baseBranch, baseCommit: form.baseCommit, harness: form.harness, model: form.model });
      if (active.current) changed();
    } catch (failure) {
      // The server read the tickets again and refused. Whatever was checked before is now unproven:
      // keeping it would offer "Register these versions" for a version the server just rejected,
      // which is how a 409 artifacts_changed was answered with the same stale pair twice.
      if (active.current) { setError(String(failure)); setReferences(null); }
    }
    finally { if (active.current) setBusy(null); }
  }
  if (!canAdminister(me) || ['active', 'retired', 'completed'].includes(item.workflowStatus)) return null;
  return <section aria-label="Prepare work item" className="work-policy-form">
    <h3>Register a prepared task</h3>
    <p>Reference a specification ticket and a single-step plan ticket in this work source. Their current versions will be checked again before approval and build.</p>
    <fieldset disabled={busy !== null}>
      {([['specification', 'Specification ticket'], ['plan', 'Plan ticket'], ['baseBranch', 'Base branch']] as const).map(([key, label]) =>
        <label className="field" key={key}>{label}<input value={form[key]} onChange={event => edit(key, event.target.value)} /></label>)}
      {/* The approval binds this commit, so it is read from the forge rather than pasted from a
          terminal. It stays editable: an operator may deliberately build an older tree. */}
      <label className="field">Base commit<input value={form.baseCommit} onChange={event => edit('baseCommit', event.target.value)} /></label>
      <button className="btn" disabled={busy !== null || !form.baseBranch.trim()} onClick={() => void readHead()}>
        {busy === 'head' ? 'Reading…' : 'Use current head'}</button>
      <label className="field">Harness<select value={form.harness} onChange={event => edit('harness', event.target.value)}>
        <option value="">{choices.harnesses.length ? 'Select a harness' : 'No harness is configured'}</option>
        {choices.harnesses.map(harness => <option key={harness} value={harness}>{harness}</option>)}
        {form.harness && !choices.harnesses.includes(form.harness) && <option value={form.harness}>{form.harness} (not configured here)</option>}
      </select></label>
      <label className="field">Model<select value={form.model} onChange={event => edit('model', event.target.value)}>
        <option value="">{choices.models.length ? 'Select a model' : 'No model is enabled'}</option>
        {choices.models.map(model => {
          const missing = unpriced(model, form.harness, choices.reportedTypes);
          return <option key={model.id} value={model.name} disabled={missing.length > 0}>
            {model.label}{model.name === model.label ? '' : ` (${model.name})`}
            {missing.length > 0 ? ` — no price for ${missingLabel(missing)}` : ''}</option>;
        })}
        {form.model && !choices.models.some(model => model.name === form.model) && <option value={form.model}>{form.model} (not in the catalogue)</option>}
      </select></label>
      <button className="btn" disabled={busy !== null || !form.specification.trim()} onClick={() => void inspect(true)}>
        {busy === 'checking' ? 'Reading…' : 'Read specification version'}</button>
      <button className="btn" disabled={busy !== null || Object.values(form).some(value => !value.trim())} onClick={() => void inspect()}>
        {busy === 'checking' ? 'Checking…' : 'Check artifact references'}</button>
      {busy && <p role="status">{busy === 'saving' ? 'Registering the checked versions. The form unlocks when the server answers.' : 'Reading the current ticket versions…'}</p>}
      {references && <div><p>Specification: {references.specification.title}</p><p>Specification SHA-256: <code>{references.specification.artifact.sha256}</code></p>
        <details><summary>Single-step plan format</summary><p>Put this JSON in the plan ticket's body and replace the step text with the prepared task.</p>
          <pre>{JSON.stringify({ schemaVersion: 1, specificationSha256: references.specification.artifact.sha256, steps: [{ id: 'step-1', instruction: 'Describe the single build task here.' }] }, null, 2)}</pre></details>
        {/* Both digests are shown: a plan that names an older specification version, and a ticket
            edited after the check, are the two refusals an operator has to be able to see. */}
        {references.plan && <><p>Plan: {references.plan.title}</p><p>Plan SHA-256: <code>{references.plan.artifact.sha256}</code></p>
          <button className="btn" onClick={() => void register()}>{busy === 'saving' ? 'Registering…' : 'Register these versions'}</button></>}
      </div>}
    </fieldset>
    {error && <p role="alert">{error}{references ? '' : ' Check the artifact references again before registering.'}</p>}
  </section>;
}
