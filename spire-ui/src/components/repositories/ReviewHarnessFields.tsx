import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchLlmModels } from '../../api';
import { preparationKeys } from '../work-items/workQueries';
import SettingField from '../SettingField';
import BuildModelFields, { modelChoices } from './factory/BuildModelFields';
import { harnessLabel } from '../harnessLabels';
import { PAY_WITH_LABEL, buildOptions, buildOptionsKey, harnessOffersModel, mayPayWithSubscription, subscriptionRefused, type PayWith } from './factory/buildDefaultsApi';
import type { ReviewHarness } from './repositoriesApi';

interface Props {
  repositoryId: string;
  value: ReviewHarness;
  /** An update, not a value: the model select changes the model and then the level in one event. */
  onChange: (update: (previous: ReviewHarness) => ReviewHarness) => void;
}

const SCOPE = 'review harness';

/**
 * The harness half of "Review with" (harness reviews, step 5): which agent image reviews this repository's pull
 * requests, how it pays, and its model and thinking level. The choices are the ones the build setup offers, read
 * only while this half is on screen.
 */
export default function ReviewHarnessFields({ repositoryId, value, onChange }: Props) {
  const optionsRead = useQuery({ queryKey: buildOptionsKey(repositoryId), queryFn: () => buildOptions(repositoryId) });
  const modelsRead = useQuery({ queryKey: preparationKeys.llmModels(), queryFn: () => fetchLlmModels() });
  const harnesses = optionsRead.data?.harnesses ?? [];
  const known = optionsRead.data?.models?.[value.harness];
  const priced = (modelsRead.data ?? []).filter(model => model.enabled);
  const choices = modelChoices(value.harness, known, priced, optionsRead.data?.reportedTypes ?? {}, value.payWith);
  const subscriptionOffered = mayPayWithSubscription(optionsRead.data?.subscriptionHarnesses, value.harness);
  const patch = (next: Partial<ReviewHarness>) => onChange(previous => ({ ...previous, ...next }));
  // The model and level belong to the harness's model list, and a payment one harness allows another may not.
  const changeHarness = (harness: string) => onChange(previous => ({ ...previous, harness, effort: null,
    model: harnessOffersModel(optionsRead.data?.models, harness, previous.model) ? previous.model : '',
    payWith: subscriptionRefused(optionsRead.data?.subscriptionHarnesses, harness) ? 'API_KEY' : previous.payWith }));
  // A stored subscription on a harness the list leaves out is not shown, so it must not be sent either.
  const loaded = optionsRead.isSuccess;
  const staleSubscription = loaded && !!value.harness && value.payWith === 'SUBSCRIPTION'
    && subscriptionRefused(optionsRead.data?.subscriptionHarnesses, value.harness);
  useEffect(() => { if (staleSubscription) onChange(previous => ({ ...previous, payWith: 'API_KEY' })); }, [staleSubscription, onChange]);
  return <>
    <SettingField label="Harness" scope={SCOPE} hint="Required. The agent image that reviews this repository's pull requests, read-only. A refused run never falls back to an API provider.">
      {/* The level belongs to the harness's model list, so it does not survive a change of harness. */}
      <select aria-label="Harness" value={value.harness} onChange={event => changeHarness(event.target.value)}>
        <option value="">{harnesses.length ? 'Select a harness' : 'No harness is configured'}</option>
        {harnesses.map(harness => <option key={harness} value={harness}>{harnessLabel(harness)}</option>)}
        {value.harness && !harnesses.includes(value.harness) && <option value={value.harness}>{harnessLabel(value.harness)} (not configured here)</option>}
      </select></SettingField>
    <SettingField label="Pay with" scope={SCOPE} hint="An API key is billed per token and needs a price for the model. A subscription uses a signed-in subscription seat at no per-token cost.">
      <select aria-label="Pay with" value={value.payWith} onChange={event => patch({ payWith: event.target.value as PayWith })}>
        <option value="API_KEY">{PAY_WITH_LABEL.API_KEY}</option>
        {
          // NOT ALLOWED — kept for history only. Anthropic's terms forbid a third-party product to route requests
          // through a Claude subscription or to hold claude.ai tokens:
          // https://code.claude.com/docs/en/legal-and-compliance (read 2026-11-08). Claude Code pays with an API key.
        }
        {subscriptionOffered && <option value="SUBSCRIPTION">{PAY_WITH_LABEL.SUBSCRIPTION}</option>}
      </select></SettingField>
    <BuildModelFields harness={value.harness} known={known} choices={choices} model={value.model} effort={value.effort ?? ''}
      scope={SCOPE} setModel={model => patch({ model, effort: null })} setEffort={effort => patch({ effort: effort || null })} />
    {optionsRead.error && <p className="prov-error" role="alert">Could not load the harnesses: {String(optionsRead.error instanceof Error ? optionsRead.error.message : optionsRead.error)}</p>}
  </>;
}
