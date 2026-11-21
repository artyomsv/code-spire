import type { CatalogRowView, LlmModelView, LlmType } from '../api';
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
}

/**
 * Settings → LLM → Models, one tab per vendor that has a registered provider, in the side panel's tab style. A model
 * of a vendor nobody has registered is not listed: it could not be chosen anywhere.
 */
export default function SettingsLlmModelTabs({ tabs, tab, onTab, models, catalogRows, actions }: SettingsLlmModelTabsProps) {
  if (!tab) {
    return <p className="prov-note">Register a provider first: models are listed under the provider of their vendor.</p>;
  }
  return (
    <>
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
