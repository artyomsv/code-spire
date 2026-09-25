import type { LlmModelView } from '../../../api';
import { TOKEN_TYPE_LABEL, unpricedTypesFor } from '../../../llmPricing';
import SettingField from '../../SettingField';
import type { HarnessModels, PayWith } from './buildDefaultsApi';

/** Why a harness has no model list, as a sentence. The empty dropdown alone could mean any of these. */
const NO_LIST: Record<string, string> = {
  UNKNOWN: 'The run worker has not said which models this harness runs yet. Showing the price list instead.',
  NO_CATALOGUE: 'This agent image does not say which models it runs — it was built without deploy/agent/build-codex.sh. Showing the price list instead.',
  UNREADABLE: 'This agent image declares a model list that could not be read. Showing the price list instead.',
  IMAGE_UNAVAILABLE: 'The agent image could not be reached, so its model list is unknown. Showing the price list instead.',
};

const missingLabel = (types: string[]) => types.map(type => TOKEN_TYPE_LABEL[type as keyof typeof TOKEN_TYPE_LABEL]).join(', ');

/** One choosable model: what it is called, and why it cannot be chosen when it cannot. */
export interface ModelChoice { value: string; label: string; blocked: string | null }

/**
 * The models to offer for this harness, and what blocks each (M3.5 part M).
 *
 * <p>When the image says what it runs, THAT is the list — the price list only decides whether each one
 * can be paid for. When it does not, the price list is all there is, which is what this screen offered
 * before, and the screen says so rather than presenting it as the harness's own list.
 */
export function modelChoices(harness: string, known: HarnessModels | undefined, priced: LlmModelView[],
                             reported: Record<string, string[]>, payWith: PayWith = 'API_KEY'): ModelChoice[] {
  // A subscription is not priced per token (M3.5 part F): no model is held back for a missing rate.
  const subscription = payWith === 'SUBSCRIPTION';
  const unpriced = (model: LlmModelView) => (harness && !subscription ? unpricedTypesFor(model, reported[harness]) : []);
  if (known?.status === 'OK') {
    return known.offered.map(model => {
      const price = priced.find(entry => entry.name === model.slug);
      const missing = price ? unpriced(price) : [];
      return {
        value: model.slug,
        label: model.displayName === model.slug ? model.slug : `${model.displayName} (${model.slug})`,
        // Not offered as runnable until it can be paid for: an API-key run needs a rate for every type
        // the harness reports, and the save would refuse it anyway.
        blocked: subscription ? null : !price ? 'no price yet — add it in Settings → LLM'
          : missing.length ? `no price for ${missingLabel(missing)}` : null,
      };
    });
  }
  return priced.map(model => {
    const missing = unpriced(model);
    return {
      value: model.name,
      label: model.name === model.label ? model.label : `${model.label} (${model.name})`,
      blocked: missing.length ? `no price for ${missingLabel(missing)}` : null,
    };
  });
}

interface Props {
  harness: string;
  known: HarnessModels | undefined;
  choices: ModelChoice[];
  model: string;
  effort: string;
  setModel: (model: string) => void;
  setEffort: (effort: string) => void;
}

/**
 * The model and its thinking level, chosen together because the levels belong to the model.
 *
 * <p>Codex shows its models as "Select Model and Effort", and the levels differ per model — measured
 * 2026-09-18, one allows low to ultra and another only low to xhigh. So the level list is rebuilt from the
 * chosen model every time, and it is offered only when the image said which levels exist: there is nothing
 * honest to offer otherwise, and the save refuses a level it cannot check.
 */
export default function BuildModelFields({ harness, known, choices, model, effort, setModel, setEffort }: Props) {
  const runs = known?.status === 'OK' ? known.offered.find(entry => entry.slug === model) : undefined;
  return <>
    {harness && known && known.status !== 'OK' && <p className="factory-note" role="status">{NO_LIST[known.status]}</p>}
    <SettingField label="Model" scope="build setup" hint="Required. The model the agent calls. An API-key run also needs a rate for every token type this harness reports, set in Settings → LLM.">
      <select aria-label="Model" value={model} onChange={event => { setModel(event.target.value); setEffort(''); }}>
        <option value="">{choices.length ? 'Select a model' : 'No model is available'}</option>
        {choices.map(choice => <option key={choice.value} value={choice.value} disabled={choice.blocked !== null}>
          {choice.label}{choice.blocked ? ` — ${choice.blocked}` : ''}</option>)}
        {model && !choices.some(choice => choice.value === model) && <option value={model}>{model} (not offered here)</option>}
      </select></SettingField>
    {runs && <SettingField label="Thinking level" scope="build setup" hint="How hard the model thinks. The levels are the ones this model offers; deeper costs more and takes longer.">
      <select aria-label="Thinking level" value={effort} onChange={event => setEffort(event.target.value)}>
        <option value="">Model default ({runs.defaultEffort})</option>
        {runs.efforts.map(level => <option key={level} value={level}>{level}</option>)}
      </select></SettingField>}
  </>;
}
