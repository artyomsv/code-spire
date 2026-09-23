import { useEffect, useRef, useState } from 'react';
import { fetchLlmModels, type LlmModelView } from '../../../api';
import SettingField from '../../SettingField';
import BuildModelFields, { modelChoices } from './BuildModelFields';
import FactoryStep from './FactoryStep';
import { buildOptions, repositoryBranchHead, saveBuildDefaults, type BuildDefaults, type HarnessModels } from './buildDefaultsApi';

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
    effort: defaults.effort ?? '',
  });
  const [choices, setChoices] = useState<{ harnesses: string[]; models: LlmModelView[]; reportedTypes: Record<string, string[]>;
    harnessModels: Record<string, HarnessModels> }>({ harnesses: [], models: [], reportedTypes: {}, harnessModels: {} });
  const [busy, setBusy] = useState<'saving' | 'head' | null>(null);
  const [error, setError] = useState(''), [head, setHead] = useState<{ commit: string; account: string } | null>(null);
  const editing = open === 'build';

  useEffect(() => {
    if (!editing) return;
    let active = true;
    Promise.all([buildOptions(repositoryId), fetchLlmModels()])
      // A wire answer of the wrong shape offers nothing rather than blanking the step.
      .then(([options, models]) => { if (active) setChoices({ harnesses: options.harnesses ?? [],
        models: (models ?? []).filter(model => model.enabled), reportedTypes: options.reportedTypes ?? {},
        harnessModels: options.models ?? {} }); })
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
      // An empty level is the model's own default, sent as null rather than as a level called "".
      await saveBuildDefaults(repositoryId, { expectedRevision: defaults.revision, ...form, effort: form.effort || null });
      if (live.current) changed(`Build setup saved: ${form.harness} on ${form.model}${form.effort ? ` (${form.effort})` : ''}, starting from ${form.baseBranch}.`);
    } catch (failure) { if (live.current) setError(String(failure instanceof Error ? failure.message : failure)); }
    finally { if (live.current) setBusy(null); }
  }

  // The chosen PAIR, not just three non-empty fields. Choosing a model and then changing the harness
  // can leave a selection the dispatch refuses; the option goes grey, and Save used to stay live.
  const known = choices.harnessModels[form.harness];
  const offered = modelChoices(form.harness, known, choices.models, choices.reportedTypes);
  const picked = offered.find(choice => choice.value === form.model);
  // An unresolved model is UNKNOWN, not complete: before the catalogue answers, and for a saved name the
  // catalogue no longer offers, there is nothing to judge — so Save waits rather than guessing.
  // A level is sent only when the chosen model visibly offers it. A saved level whose list is no longer
  // known has no control on screen, so it is named below with a way to drop it, and Save waits.
  const levels = known?.status === 'OK' ? known.offered.find(model => model.slug === form.model)?.efforts ?? [] : [];
  const levelUnusable = !!form.effort && !levels.includes(form.effort);
  const complete = !!form.baseBranch.trim() && !!form.harness && !!picked && picked.blocked === null && !levelUnusable;
  return <FactoryStep number={5} question="How it builds" term="build setup" state={editing ? 'editing' : defaults.revision > 0 ? 'done' : 'missing'}
    actions={!editing && <button className={defaults.revision > 0 ? 'btn-ghost sm' : 'btn sm'} type="button" disabled={open !== null}
      onClick={() => setOpen('build')}>{defaults.revision > 0 ? 'Change' : 'Set up the build'}</button>}>
    {defaults.revision > 0
      ? <div className="factory-row"><b>{defaults.harness} · {defaults.model}{defaults.effort ? ` · ${defaults.effort}` : ''}</b><span className="prov-sub">starts from {defaults.baseBranch}</span></div>
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
        {/* The level belongs to the harness's model list, so it does not survive a change of harness. */}
        <select aria-label="Harness" value={form.harness} onChange={event => setForm(previous => ({ ...previous, harness: event.target.value, effort: '' }))}>
          <option value="">{choices.harnesses.length ? 'Select a harness' : 'No harness is configured'}</option>
          {choices.harnesses.map(harness => <option key={harness} value={harness}>{harness}</option>)}
          {form.harness && !choices.harnesses.includes(form.harness) && <option value={form.harness}>{form.harness} (not configured here)</option>}
        </select></SettingField>
      <BuildModelFields harness={form.harness} known={known} choices={offered} model={form.model} effort={form.effort}
        setModel={model => setForm(previous => ({ ...previous, model }))}
        setEffort={effort => setForm(previous => ({ ...previous, effort }))} />
      {/* A select whose every option is disabled ignores clicks and keys alike, and looked broken (operator,
          2026-09-23). Say why nothing can be chosen, and where to fix it. */}
      {form.harness && offered.length > 0 && offered.every(choice => choice.blocked !== null) && <p className="prov-error">
        No model can be picked yet: each one is missing a price. Add the rates for one in Settings → LLM.</p>}
      {picked?.blocked && <p className="prov-error" role="alert">
        {form.model} has {picked.blocked}. {form.harness} reports those token types, and an API-key run needs a rate
        for each — or a mark in Settings → LLM that the vendor does not bill it.</p>}
      {levelUnusable && picked && <p className="prov-error" role="alert">
        The thinking level {form.effort} cannot be checked or is not offered for {form.model} here.{' '}
        <button className="btn-ghost sm" type="button" onClick={() => setForm(previous => ({ ...previous, effort: '' }))}>Use the model default</button></p>}
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
