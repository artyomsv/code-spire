import type { CatalogRowView, LlmModelView, LlmType } from '../api';
import type { HarnessKeysRead } from '../hooks/useModelTab';
import type { ModelTab } from '../llmModelTabs';
import SettingsLlmModelsTable, { type ModelRowActions } from './SettingsLlmModelsTable';

interface SettingsLlmModelTabsProps {
  tabs: ModelTab[];
  /** The selected tab; null when no provider is registered. */
  tab: ModelTab | null;
  onTab: (type: LlmType) => void;
  models: LlmModelView[];
  catalogRows: CatalogRowView[];
  actions: ModelRowActions;
  /** The harness-key read: a failure hides harness-key tabs, so it is shown with a retry, never swallowed. */
  keysRead: HarnessKeysRead;
}

/** The harness keys could not be read: the vendors used only through them have no tab until they are. */
function KeysReadFailed({ keysRead }: { keysRead: HarnessKeysRead }) {
  if (!keysRead.failure) return null;
  return (
    <p className="prov-error" role="alert">
      Harness keys could not be loaded ({keysRead.failure}), so models of vendors used only through them are
      hidden.{' '}
      <button type="button" className="btn-ghost" onClick={keysRead.retry}>Retry loading the harness keys</button>
    </p>
  );
}

/**
 * Settings → LLM → Models, one tab per vendor that has a registered provider or harness key, in the side panel's tab
 * style. A model of a vendor nobody has registered is not listed: it could not be chosen anywhere.
 */
export default function SettingsLlmModelTabs({ tabs, tab, onTab, models, catalogRows, actions, keysRead }: SettingsLlmModelTabsProps) {
  if (!tab) {
    return <>
      <KeysReadFailed keysRead={keysRead} />
      <p className="prov-note">Register a provider first: models are listed under the provider of their vendor.</p>
    </>;
  }
  return (
    <>
      <KeysReadFailed keysRead={keysRead} />
      <div className="panel-tabs" role="tablist" aria-label="Models by provider">
        {tabs.map((entry) => (
          <button key={entry.type} type="button" role="tab" aria-selected={entry.type === tab.type}
            className={entry.type === tab.type ? 'panel-tab on' : 'panel-tab'} onClick={() => onTab(entry.type)}>
            {entry.label}
          </button>
        ))}
      </div>
      <SettingsLlmModelsTable models={models} catalogRows={catalogRows} actions={actions} type={tab.type} />
    </>
  );
}
