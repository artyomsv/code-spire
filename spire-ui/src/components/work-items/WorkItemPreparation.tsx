import { useEffect, useRef, useState } from 'react';
import type { WorkItemDetail } from '../../api';
import { canAdminister } from '../../auth';
import { useMe } from '../../hooks/useMe';
import { resolveArtifact, registerPreparation, type ArtifactReference } from './workPreparationApi';

export default function WorkItemPreparation({ item, changed }: { item: WorkItemDetail; changed: () => void }) {
  const { me } = useMe();
  const active = useRef(true), sequence = useRef(0);
  useEffect(() => { active.current = true; return () => { active.current = false; sequence.current++; }; }, []);
  const [form, setForm] = useState({ specification: item.preparation?.specification.location.issueKey ?? '', plan: item.preparation?.plan.location.issueKey ?? '',
    baseBranch: item.preparation?.baseBranch ?? '', baseCommit: item.preparation?.baseCommit ?? '', harness: item.preparation?.harness ?? '', model: item.preparation?.model ?? '' });
  const [references, setReferences] = useState<{ specification: ArtifactReference; plan: ArtifactReference | null } | null>(null);
  const [busy, setBusy] = useState<'checking' | 'saving' | null>(null), [error, setError] = useState('');
  function edit(key: keyof typeof form, value: string) { sequence.current++; setForm(previous => ({ ...previous, [key]: value })); setReferences(null); setBusy(null); }
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
    } catch (failure) { if (active.current) setError(String(failure)); }
    finally { if (active.current) setBusy(null); }
  }
  if (!canAdminister(me) || ['active', 'retired', 'completed'].includes(item.workflowStatus)) return null;
  return <section aria-label="Prepare work item" className="work-policy-form">
    <h3>Register a prepared task</h3>
    <p>Reference a specification ticket and a single-step plan ticket in this work source. Their current versions will be checked again before approval and build.</p>
    <fieldset disabled={busy === 'saving'}>
      {([['specification', 'Specification ticket'], ['plan', 'Plan ticket'], ['baseBranch', 'Base branch'], ['baseCommit', 'Base commit'], ['harness', 'Harness'], ['model', 'Model']] as const).map(([key, label]) =>
        <label className="field" key={key}>{label}<input value={form[key]} onChange={event => edit(key, event.target.value)} /></label>)}
      <button className="btn" disabled={busy !== null || !form.specification.trim()} onClick={() => void inspect(true)}>Read specification version</button>
      <button className="btn" disabled={busy !== null || Object.values(form).some(value => !value.trim())} onClick={() => void inspect()}>Check artifact references</button>
      {references && <div><p>Specification: {references.specification.title}</p><p>Specification SHA-256: <code>{references.specification.artifact.sha256}</code></p>
        <details><summary>Single-step plan format</summary><p>Put this JSON in the plan ticket's body and replace the step text with the prepared task.</p>
          <pre>{JSON.stringify({ schemaVersion: 1, specificationSha256: references.specification.artifact.sha256, steps: [{ id: 'step-1', instruction: 'Describe the single build task here.' }] }, null, 2)}</pre></details>
        {references.plan && <><p>Plan: {references.plan.title}</p><button className="btn" onClick={() => void register()}>Register these versions</button></>}
      </div>}
    </fieldset>
    {error && <p role="alert">{error}</p>}
  </section>;
}
