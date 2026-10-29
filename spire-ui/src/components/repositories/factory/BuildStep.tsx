import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchLlmModels, type LlmModelView } from '../../../api';
import { preparationKeys } from '../../work-items/workQueries';
import { writeLive } from '../../../liveCache';
import SettingField from '../../SettingField';
import BuildModelFields, { modelChoices } from './BuildModelFields';
import FactoryStep from './FactoryStep';
import { buildDefaultsKey, buildOptions, buildOptionsKey, repositoryBranchHead, saveBuildDefaults, type BuildDefaults, type HarnessModels, type PayWith, type Preparation } from './buildDefaultsApi';

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
/** What the form offers: the harnesses and models this deployment can run. */
interface Choices {
  harnesses: string[]; models: LlmModelView[]; reportedTypes: Record<string, string[]>;
  harnessModels: Record<string, HarnessModels>; verifyMaxSeconds?: number; preparations: Preparation[];
}
const NO_CHOICES: Choices = { harnesses: [], models: [], reportedTypes: {}, harnessModels: {}, preparations: [] };

/** How a build pays, in the words the setup shows. */
const PAY_WITH_LABEL: Record<PayWith, string> = { API_KEY: 'an API key', SUBSCRIPTION: 'a Codex subscription' };
/** How the specification and plan are made (M4 slice 3, §6.1). */
const PREPARATION_LABEL: Record<Preparation, string> = { composed: 'Copy the ticket as one step', generated: 'Write them with the build model' };
/** The verify limit a setup takes when none was saved; the server bounds it by the deployment's wall clock. */
const DEFAULT_VERIFY_SECONDS = 1800;
const verifyLines = (text: string) => text.split('\n').map(line => line.trim()).filter(line => line.length > 0);

export default function BuildStep({ repositoryId, defaults, open, setOpen, changed, reload }: Props) {
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  const [form, setForm] = useState({
    baseBranch: defaults.baseBranch ?? '', harness: defaults.harness ?? '', model: defaults.model ?? '',
    effort: defaults.effort ?? '', payWith: (defaults.payWith ?? 'API_KEY') as PayWith, preparation: (defaults.preparation ?? 'composed') as Preparation,
    // One command per line on screen; a list on the wire (M4).
    verifyCommands: (defaults.verifyCommands ?? []).join('\n'), verifyTimeoutSeconds: String(defaults.verifyTimeoutSeconds ?? DEFAULT_VERIFY_SECONDS),
  });
  const [busy, setBusy] = useState<'saving' | 'head' | null>(null);
  const [error, setError] = useState(''), [head, setHead] = useState<{ commit: string; account: string } | null>(null);
  const editing = open === 'build';
  const client = useQueryClient();
  // Read only while the form is open, and both or neither: the selects fall back to what is already saved, and the save
  // still refuses an unrunnable pair.
  const optionsRead = useQuery({ queryKey: buildOptionsKey(repositoryId), queryFn: () => buildOptions(repositoryId), enabled: editing });
  const modelsRead = useQuery({ queryKey: preparationKeys.llmModels(), queryFn: () => fetchLlmModels(), enabled: editing });
  const options = optionsRead.data, models = modelsRead.data;
  // A wire answer of the wrong shape offers nothing rather than blanking the step.
  const choices: Choices = options && models
    ? { harnesses: options.harnesses ?? [], models: (models ?? []).filter(model => model.enabled), reportedTypes: options.reportedTypes ?? {},
      harnessModels: options.models ?? {}, verifyMaxSeconds: options.verifyMaxSeconds, preparations: options.preparations ?? [] }
    : NO_CHOICES;
  // The server offers generated only while it can honour it (M4 slice 3); otherwise the choice is not shown.
  const offersGenerated = choices.preparations.includes('generated');

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
      // Blank lines are dropped here as the server drops them: a blank command would run, exit 0 and pass.
      // The preparation is sent only when the choice is on screen: none keeps what is stored, so a server that
      // stopped offering generated is never switched back by a form that could not show it.
      const { preparation, ...coordinates } = form;
      const saved = await saveBuildDefaults(repositoryId, { expectedRevision: defaults.revision, ...coordinates, effort: form.effort || null,
        verifyCommands: verifyLines(form.verifyCommands), verifyTimeoutSeconds: Number(form.verifyTimeoutSeconds),
        ...(offersGenerated ? { preparation } : {}) });
      // The saved row goes into its key first, so the list and the preparation form are right even if the re-read fails.
      // Written whether or not this form is still open, and after cancelling an older read that would overwrite it.
      writeLive(client, buildDefaultsKey(repositoryId), saved);
      if (live.current) changed(`Build setup saved: ${form.harness} on ${form.model}${form.effort ? ` (${form.effort})` : ''}, paid with ${PAY_WITH_LABEL[form.payWith]}, starting from ${form.baseBranch}.`);
    } catch (failure) { if (live.current) setError(String(failure instanceof Error ? failure.message : failure)); }
    finally { if (live.current) setBusy(null); }
  }

  // The chosen PAIR, not just three non-empty fields. Choosing a model and then changing the harness
  // can leave a selection the dispatch refuses; the option goes grey, and Save used to stay live.
  const known = choices.harnessModels[form.harness];
  const offered = modelChoices(form.harness, known, choices.models, choices.reportedTypes, form.payWith);
  const picked = offered.find(choice => choice.value === form.model);
  // An unresolved model is UNKNOWN, not complete: before the catalogue answers, and for a saved name the
  // catalogue no longer offers, there is nothing to judge — so Save waits rather than guessing.
  // A level is sent only when the chosen model visibly offers it. A saved level whose list is no longer
  // known has no control on screen, so it is named below with a way to drop it, and Save waits.
  const levels = known?.status === 'OK' ? known.offered.find(model => model.slug === form.model)?.efforts ?? [] : [];
  const levelUnusable = !!form.effort && !levels.includes(form.effort);
  const complete = !!form.baseBranch.trim() && !!form.harness && !!picked && picked.blocked === null && !levelUnusable;
  // Save stays off until these are filled; saying which is what an operator needs (feedback, 2026-09-27).
  const missing = [!form.baseBranch.trim() && 'Base branch', !form.harness && 'Harness', form.harness && !form.model && 'Model']
    .filter((field): field is string => !!field);
  return <FactoryStep number={5} question="How it builds" term="build setup" state={editing ? 'editing' : defaults.revision > 0 ? 'done' : 'missing'}
    actions={!editing && <button className={defaults.revision > 0 ? 'btn-ghost sm' : 'btn sm'} type="button" disabled={open !== null}
      onClick={() => setOpen('build')}>{defaults.revision > 0 ? 'Change' : 'Set up the build'}</button>}>
    {defaults.revision > 0
      ? <div className="factory-row"><b>{defaults.harness} · {defaults.model}{defaults.effort ? ` · ${defaults.effort}` : ''}</b><span className="prov-sub">starts from {defaults.baseBranch} · paid with {PAY_WITH_LABEL[(defaults.payWith ?? 'API_KEY') as PayWith]}
        {defaults.verifyCommands?.length ? ` · checks: ${defaults.verifyCommands.join(', ')}` : ' · no checks'}</span></div>
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
      <SettingField label="Pay with" scope="build setup" hint="An API key is billed per token and needs a price for the model. A subscription uses a signed-in Codex seat at no per-token cost; the subscription's own limits apply.">
        <select aria-label="Pay with" value={form.payWith}
          onChange={event => setForm(previous => ({ ...previous, payWith: event.target.value as PayWith }))}>
          <option value="API_KEY">{PAY_WITH_LABEL.API_KEY}</option>
          <option value="SUBSCRIPTION">{PAY_WITH_LABEL.SUBSCRIPTION}</option>
        </select></SettingField>
      <BuildModelFields harness={form.harness} known={known} choices={offered} model={form.model} effort={form.effort}
        setModel={model => setForm(previous => ({ ...previous, model }))}
        setEffort={effort => setForm(previous => ({ ...previous, effort }))} />
      {/* Shown only when the server offers generated, so a deployment that cannot write them never shows a choice it
          would refuse. The generated option names the runner: the build's own harness, model and payment (§6.1). */}
      {offersGenerated && <fieldset className="factory-form" aria-label="Specification and plan">
        <legend className="field-label">Specification and plan</legend>
        {(Object.keys(PREPARATION_LABEL) as Preparation[]).map(preparation => <label key={preparation} className="field-check">
          <input type="radio" name={`preparation-${repositoryId}`} value={preparation} checked={form.preparation === preparation}
            onChange={() => setForm(previous => ({ ...previous, preparation }))} />
          <span>{PREPARATION_LABEL[preparation]}{preparation === 'generated' && <span className="prov-sub">
            {' '}runs on {form.harness || 'the harness above'} · {form.model || 'the model above'} · {form.payWith === 'SUBSCRIPTION' ? 'seat' : 'API key'}</span>}</span></label>)}
        {/* A20: under profiles where spec and plan run without approval, this spends before anyone is asked. */}
        <p className="factory-note">Before the first approval this spends two short runs, and up to one automatic retry each, when the
          specification and plan phases are set to run without approval.</p>
      </fieldset>}
      <SettingField label="Check commands" scope="build setup" hint="One command per line, run in order on a clean copy of the build. The first failure stops the rest; only a build whose checks all pass is pushed.">
        <textarea aria-label="Check commands" rows={4} value={form.verifyCommands} placeholder="./gradlew check"
          onChange={event => setForm(previous => ({ ...previous, verifyCommands: event.target.value }))} /></SettingField>
      {verifyLines(form.verifyCommands).length === 0 && <p className="factory-note" role="status">No checks: builds will stop as unverified.</p>}
      <SettingField label="Verify time limit (seconds)" scope="build setup" hint={`For all checks together. At most ${choices.verifyMaxSeconds ?? DEFAULT_VERIFY_SECONDS}.`}>
        <input aria-label="Verify time limit (seconds)" type="number" min={60} max={choices.verifyMaxSeconds ?? DEFAULT_VERIFY_SECONDS}
          value={form.verifyTimeoutSeconds} onChange={event => setForm(previous => ({ ...previous, verifyTimeoutSeconds: event.target.value }))} /></SettingField>
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
      {busy === null && missing.length > 0 && <p className="factory-note" role="status">To save, fill in: {missing.join(', ')}.</p>}
      <div className="prov-actions">
        <button className="btn" type="button" disabled={busy !== null || !complete} onClick={() => void submit()}>{busy === 'saving' ? 'Saving…' : 'Save build setup'}</button>
        <button className="btn-ghost" type="button" disabled={busy !== null} onClick={() => setOpen(null)}>Cancel</button></div>
    </fieldset>}
  </FactoryStep>;
}
