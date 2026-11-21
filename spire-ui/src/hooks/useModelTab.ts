import { useSearchParams } from 'react-router';
import type { LlmProviderView, LlmType } from '../api';
import { chosenTab, modelTabs } from '../llmModelTabs';

/**
 * The Models tab, kept in the URL (`?models=<type>`) like the screen's `?edit=` link, so a re-read of the providers
 * or the models never resets it. A type with no provider left falls back to the first tab.
 */
export function useModelTab(providers: LlmProviderView[]) {
  const [params, setParams] = useSearchParams();
  const tabs = modelTabs(providers);
  const tab = chosenTab(tabs, params.get('models'));
  function select(type: LlmType) {
    const next = new URLSearchParams(params);
    next.set('models', type);
    setParams(next, { replace: true });
  }
  return { tabs, tab, select };
}
