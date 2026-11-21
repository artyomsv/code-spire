import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { fetchHarnessCredentials, harnessCredentialsKey, type LlmProviderView, type LlmType } from '../api';
import { chosenTab, modelTabs } from '../llmModelTabs';

/** The harness-key read as the Models card shows it: why it failed (null when it has not), and a retry. */
export interface HarnessKeysRead {
  failure: string | null;
  retry: () => void;
}

function failureOf(error: unknown): string | null {
  if (!error) return null;
  return error instanceof Error ? error.message : String(error);
}

/**
 * The Models tab, kept in the URL (`?models=<type>`) like the screen's `?edit=` link, so a re-read of the providers
 * or the models never resets it. A type with no provider or harness key left falls back to the first tab.
 *
 * <p>The harness keys are read through the Harness keys screen's own query key. A failed read gives no harness-key
 * tab and never fewer API-provider tabs, and it is returned, so the card says so and offers a retry.
 */
export function useModelTab(providers: LlmProviderView[]) {
  const [params, setParams] = useSearchParams();
  const credentials = useQuery({ queryKey: harnessCredentialsKey, queryFn: () => fetchHarnessCredentials() });
  const tabs = modelTabs(providers, credentials.data ?? []);
  const tab = chosenTab(tabs, params.get('models'));
  function select(type: LlmType) {
    const next = new URLSearchParams(params);
    next.set('models', type);
    setParams(next, { replace: true });
  }
  const keysRead: HarnessKeysRead = { failure: failureOf(credentials.error), retry: () => void credentials.refetch() };
  return { tabs, tab, select, keysRead };
}
