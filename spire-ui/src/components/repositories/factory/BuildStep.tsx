import { useEffect, useRef, useState } from 'react';
import { fetchLlmModels, type LlmModelView } from '../../../api';
import { TOKEN_TYPE_LABEL, unpricedTypesFor } from '../../../llmPricing';
import SettingField from '../../SettingField';
import FactoryStep from './FactoryStep';
import { buildOptions, repositoryBranchHead, saveBuildDefaults, type BuildDefaults } from './buildDefaultsApi';

interface Props {
  repositoryId: string;
  defaults: BuildDefaults;
  open: string | null;
  setOpen: (open: string | null) => void;
  changed: (notice?: string) => void;
  /** Re-reads the tab. A save refused for a stale revision can only be retried against the current one. */
  reload: () => void;
}

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

/**
 * How this repository builds: the coordinates every prepared task copies (M3.5 part B).
 *
 * <p>They were typed per work item until now — a branch, forty hex characters of commit, a harness name
 * and a model name, four free-text fields per ticket, each refused at dispatch if it was wrong. Saved
 * here once, they are offered as what this deployment can actually run, and the same refusals arrive
 * where they can be fixed.
 */
export default function BuildStep({ repositoryId, defaults, open, setOpen, changed, reload }: Props) {
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  const [form, setForm] = useState({
    baseBranch: defaults.baseBranch ?? '', harness: defaults.harness ?? '', model: defaults.model ?? '',
  });
  const [choices, setChoices] = useState<{ harnesses: string[]; models: LlmModelView[]; reportedTypes: Record<string, string[]> }>(
    { harnesses: [], models: [], reportedTypes: {} });
  const [busy, setBusy] = useState<'saving' | 'head' | null>(null);
  const [error, setError] = useState(''), [head, setHead] = useState<{ commit: string; account: string } | null>(null);
  const editing = open === 'build';

  useEffect(() => {
    if (!editing) return;
    let active = true;
    Promise.all([buildOptions(repositoryId), fetchLlmModels()])
      // A wire answer of the wrong shape offers nothing rather than blanking the step.
      .then(([options, models]) => { if (active) setChoices({ harnesses: options.harnesses ?? [],
        models: (models ?? []).filter(model => model.enabled), reportedTypes: options.reportedTypes ?? {} }); })
      .catch(() => { /* the selects fall back to what is already saved; the save still refuses an unrunnable pair */ });
    return () => { active = false; };
  }, [repositoryId, editing]);

  async function readHead() {
    setBusy('head'); setError(''); setHead(null);
    try {
      const answer = await repositoryBranchHead(repositoryId, form.baseBranch);
      if (live.current) { setHead({ commit: answer.commit, account: answer.account }); setForm(previous => ({ ...previous, baseBranch: answer.branch })); }
    } catch (failure) { if (live.current) setError(String(failure instanceof Error ? failure.message : failure)); }
    finally { if (live.current) setBusy(null); }
  }

  async function submit() {
    setBusy('saving'); setError('');
    try {
      await saveBuildDefaults(repositoryId, { expectedRevision: defaults.revision, ...form });
      if (live.current) changed(`Build setup saved: ${form.harness} on ${form.model}, starting from ${form.baseBranch}.`);
    } catch (failure) { if (live.current) setError(String(failure instanceof Error ? failure.message : failure)); }
    finally { if (live.current) setBusy(null); }
  }

  // The chosen PAIR, not just three non-empty fields. Choosing a model and then changing the harness
  // can leave a selection the dispatch refuses; the option goes grey, and Save used to stay live.
  const chosen = choices.models.find(model => model.name === form.model);
  const missing = chosen ? unpriced(chosen, form.harness, choices.reportedTypes) : [];
  // An unresolved model is UNKNOWN, not complete: before the catalogue answers, and for a saved name the
  // catalogue no longer offers, there is nothing to judge — so Save waits rather than guessing.
  const complete = !!form.baseBranch.trim() && !!form.harness && !!chosen && missing.length === 0;
  return <FactoryStep number={5} question="How it builds" term="build setup" state={editing ? 'editing' : defaults.revision > 0 ? 'done' : 'missing'}
    actions={!editing && <button className={defaults.revision > 0 ? 'btn-ghost sm' : 'btn sm'} type="button" disabled={open !== null}
      onClick={() => setOpen('build')}>{defaults.revision > 0 ? 'Change' : 'Set up the build'}</button>}>
    {defaults.revision > 0
      ? <div className="factory-row"><b>{defaults.harness} · {defaults.model}</b><span className="prov-sub">starts from {defaults.baseBranch}</span></div>
      : <p className="factory-note">Not set, so every ticket has to be given a branch, a harness and a model by hand.</p>}
    <p className="factory-note">A prepared task copies these. Changing them here never changes a decision that is already open.</p>
    {editing && <fieldset className="form-lock factory-form" aria-label="Set up the build" disabled={busy !== null}>
      <SettingField label="Base branch" scope="build setup" hint="Required. The branch a build starts from. Its head commit is pinned when a task is prepared.">
        <input aria-label="Base branch" value={form.baseBranch} placeholder="main"
          onChange={event => { setHead(null); setForm(previous => ({ ...previous, baseBranch: event.target.value })); }} /></SettingField>
      <div className="prov-actions">
        <button className="btn-ghost sm" type="button" disabled={busy !== null || !form.baseBranch.trim()} onClick={() => void readHead()}>
          {busy === 'head' ? 'Reading…' : 'Check this branch'}</button>
        {head && <span className="prov-sub">head <span className="mono">{head.commit.slice(0, 7)}</span>{head.account === 'REVIEWER' ? ' · read with the reviewer account; no usable factory account was available' : ''}</span>}
      </div>
      <SettingField label="Harness" scope="build setup" hint="Required. The agent image this deployment runs. A name without an image is refused before a run starts.">
        <select aria-label="Harness" value={form.harness} onChange={event => setForm(previous => ({ ...previous, harness: event.target.value }))}>
          <option value="">{choices.harnesses.length ? 'Select a harness' : 'No harness is configured'}</option>
          {choices.harnesses.map(harness => <option key={harness} value={harness}>{harness}</option>)}
          {form.harness && !choices.harnesses.includes(form.harness) && <option value={form.harness}>{form.harness} (not configured here)</option>}
        </select></SettingField>
      <SettingField label="Model" scope="build setup" hint="Required. The model the agent calls. Every token type this harness reports needs a price, or a 'the vendor does not bill this' mark in Settings → LLM. A run is refused before it starts otherwise.">
        <select aria-label="Model" value={form.model} onChange={event => setForm(previous => ({ ...previous, model: event.target.value }))}>
          <option value="">{choices.models.length ? 'Select a model' : 'No model is enabled'}</option>
          {choices.models.map(model => {
            const missing = unpriced(model, form.harness, choices.reportedTypes);
            return <option key={model.id} value={model.name} disabled={missing.length > 0}>
              {model.label}{model.name === model.label ? '' : ` (${model.name})`}
              {missing.length > 0 ? ` — no price for ${missingLabel(missing)}` : ''}</option>;
          })}
          {form.model && !choices.models.some(model => model.name === form.model) && <option value={form.model}>{form.model} (not in the catalogue)</option>}
        </select></SettingField>
      {missing.length > 0 && <p className="prov-error" role="alert">
        {chosen?.label ?? form.model} has no price for {missingLabel(missing)}, which {form.harness} reports.
        Enter each rate in Settings → LLM, or mark the type as one this vendor does not bill.</p>}
      {error && <p className="prov-error" role="alert">{error}</p>}
      {/* A stale revision cannot be retried from this form: every attempt resends the number it loaded. */}
      {error.includes('Reload it') && <div className="prov-actions">
        <button className="btn-ghost sm" type="button" onClick={reload}>Reload the saved setup</button></div>}
      {busy === 'saving' && <p className="factory-note" role="status">Saving the build setup. The form unlocks when the server answers.</p>}
      <div className="prov-actions">
        <button className="btn" type="button" disabled={busy !== null || !complete} onClick={() => void submit()}>{busy === 'saving' ? 'Saving…' : 'Save build setup'}</button>
        <button className="btn-ghost" type="button" disabled={busy !== null} onClick={() => setOpen(null)}>Cancel</button></div>
    </fieldset>}
  </FactoryStep>;
}
