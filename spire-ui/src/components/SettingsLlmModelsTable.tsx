import { Library } from 'lucide-react';
import type { CatalogModelView, CatalogRowView, LlmModelView } from '../api';
import { catalogNames, catalogRatesText, catalogStatus } from '../llmCatalog';
import IconButton from './IconButton';
import { profileHint, ratesSummary } from '../llmPricing';

/** The actions a row may offer; the parent owns the dialogs they open. */
export interface ModelRowActions {
  current: boolean;
  onEdit: (model: LlmModelView) => void;
  onDelete: (model: LlmModelView) => void;
  onUseCatalog: (model: LlmModelView) => void;
  onOpenCatalog: (model: CatalogModelView) => void;
}

interface SettingsLlmModelsTableProps {
  models: LlmModelView[];
  catalogRows: CatalogRowView[];
  actions: ModelRowActions;
}

interface ModelRow {
  key: string;
  name: string;
  label: string;
  type: string;
  status: string;
  rates: { text: string; warn: boolean };
  manual: LlmModelView | null;
  catalog: CatalogModelView | null;
  overrides: boolean;
}

function manualStatus(model: LlmModelView, overrides: boolean): string {
  const state = model.enabled ? 'Active' : 'Disabled';
  return overrides ? `${state} · overrides catalog` : state;
}

/** Manual models and every catalog entry in one list, by name; a manual model sits above the entry it shadows. */
export function modelRows(models: LlmModelView[], catalogRows: CatalogRowView[]): ModelRow[] {
  const names = catalogNames(catalogRows);
  const manual: ModelRow[] = models.filter((m) => m.source === 'MANUAL').map((m) => ({
    key: `manual:${m.name}`, name: m.name, label: m.label, type: m.type, status: manualStatus(m, names.has(m.name)),
    rates: ratesSummary(m), manual: m, catalog: null, overrides: names.has(m.name),
  }));
  const catalog: ModelRow[] = catalogRows.map((row) => ({
    key: `catalog:${row.model.modelId}`, name: row.model.modelId, label: row.model.displayName, type: row.model.provider,
    status: catalogStatus(row), rates: { text: catalogRatesText(row.model), warn: false }, manual: null,
    catalog: row.model, overrides: false,
  }));
  return [...manual, ...catalog].sort((a, b) => a.name.localeCompare(b.name) || (a.manual ? -1 : 1));
}

/** Settings → LLM → Models: manual rows keep Edit and Delete; catalog rows open a read-only detail. */
export default function SettingsLlmModelsTable({ models, catalogRows, actions }: SettingsLlmModelsTableProps) {
  return (
    <table className="prov-table">
      <thead>
        <tr>
          <th>Model</th>
          <th>Provider</th>
          <th>Source</th>
          <th>Status</th>
          <th>Rates / 1M tokens</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {modelRows(models, catalogRows).map((row) => (
          <tr key={row.key}>
            <td>
              <ModelName row={row} onOpenCatalog={actions.onOpenCatalog} />
            </td>
            <td className="mono">{row.type}</td>
            <td>{row.manual ? 'Manual' : 'Catalog'}</td>
            <td>{row.status}</td>
            <td className={`mono${row.rates.warn ? ' rate-unset' : ''}`}>{row.rates.text}</td>
            <td>{row.manual && <ManualActions model={row.manual} overrides={row.overrides} actions={actions} />}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ModelName({ row, onOpenCatalog }: { row: ModelRow; onOpenCatalog: (model: CatalogModelView) => void }) {
  const name = <span className="mono" style={{ color: 'var(--text-3)', fontSize: 11 }}>{row.name}</span>;
  if (row.catalog) {
    const model = row.catalog;
    return (
      <button type="button" className="btn-ghost" onClick={() => onOpenCatalog(model)}
        aria-label={`Catalog model ${row.name}`}>
        <Library size={13} aria-hidden="true" /> {row.label} {name}
      </button>
    );
  }
  return (
    <>
      {row.label} {name}
      {row.manual && profileHint(row.manual) && <div className="prov-sub">{profileHint(row.manual)}</div>}
    </>
  );
}

function ManualActions({ model, overrides, actions }: { model: LlmModelView; overrides: boolean; actions: ModelRowActions }) {
  return (
    <div className="prov-actions">
      {overrides && (
        <button type="button" className="btn-ghost" disabled={!actions.current} onClick={() => actions.onUseCatalog(model)}>
          Use catalog
        </button>
      )}
      <IconButton kind="edit" onClick={() => actions.onEdit(model)} title="Edit" aria-label="Edit" disabled={!actions.current} />
      <IconButton kind="delete" disabled={!actions.current} onClick={() => actions.onDelete(model)} title="Delete"
        aria-label="Delete" />
    </div>
  );
}
