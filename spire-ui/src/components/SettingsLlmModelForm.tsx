import { useState } from 'react';
import { createLlmModel, updateLlmModel, type LlmModelInput, type LlmModelView, type LlmType } from '../api';
import type { RateType } from '../llmPricing';
import Select from './Select';
import SettingsLlmModelDialectFields, { type ApiDialect } from './SettingsLlmModelDialectFields';
import ModelRateFields, {
  initialRates,
  ratesPayload,
  validateRates,
  type EditablePricingMode,
} from './SettingsLlmModelRateFields';

// Phase 1: OpenAI only. Anthropic/Gemini land in phase 2. Duplicated from SettingsLlmProviders
// (which owns the provider form's identical Type select) rather than shared, to keep this file
// self-contained after the extraction — both lists are a single line and change together anyway.
const LLM_TYPES: LlmType[] = ['openai', 'anthropic', 'gemini'];

/** The three identity fields grouped into one state slot to stay under the 8-`useState` limit —
 *  they always change together (one form, one submit) and nothing here needs its own effect. */
interface ModelIdentity {
  type: LlmType;
  name: string;
  label: string;
}

interface SettingsLlmModelFormProps {
  initial: LlmModelView | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}

export default function SettingsLlmModelForm({ initial, onClose, onSaved }: SettingsLlmModelFormProps) {
  const editing = initial !== null;
  const [identity, setIdentity] = useState<ModelIdentity>({
    type: initial?.type ?? 'openai',
    name: initial?.name ?? '',
    label: initial?.label ?? '',
  });
  const [pricingMode, setPricingMode] = useState<EditablePricingMode>(initial?.pricingMode ?? 'METERED');
  const [rates, setRates] = useState<Record<RateType, string>>(() => initialRates(initial));
  const [dialect, setDialect] = useState<ApiDialect>({
    outputTokenParam: initial?.outputTokenParam ?? 'MAX_TOKENS',
    supportsTemperature: initial?.supportsTemperature ?? true,
    reasoningEffort: initial?.reasoningEffort ?? '',
  });
  const [extraParams, setExtraParams] = useState(
    initial && initial.extraParams && Object.keys(initial.extraParams).length > 0
      ? JSON.stringify(initial.extraParams, null, 2)
      : '',
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function updateIdentity(patch: Partial<ModelIdentity>) {
    setIdentity((prev) => ({ ...prev, ...patch }));
  }

  function updateDialect(patch: Partial<ApiDialect>) {
    setDialect((prev) => ({ ...prev, ...patch }));
  }

  function setRate(type: RateType, value: string) {
    setRates((prev) => ({ ...prev, [type]: value }));
  }

  function parseExtraParams(): Record<string, unknown> | null {
    if (!extraParams.trim()) return {};
    let value: unknown;
    try {
      value = JSON.parse(extraParams);
    } catch {
      setError('Extra params must be valid JSON.');
      return null;
    }
    if (typeof value !== 'object' || value === null || Array.isArray(value)) {
      setError('Extra params must be a JSON object, e.g. {"service_tier": "flex"}.');
      return null;
    }
    return value as Record<string, unknown>;
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const rateError = validateRates(pricingMode, rates);
    if (rateError) {
      setError(rateError);
      return;
    }
    const parsedExtra = parseExtraParams();
    if (parsedExtra === null) return;
    setBusy(true);
    setError(null);
    const input: LlmModelInput = {
      type: identity.type,
      name: identity.name.trim(),
      label: identity.label.trim() || identity.name.trim(),
      pricingMode,
      rates: ratesPayload(pricingMode, rates),
      outputTokenParam: dialect.outputTokenParam,
      supportsTemperature: dialect.supportsTemperature,
      reasoningEffort: dialect.reasoningEffort.trim() || null,
      extraParams: parsedExtra,
      enabled: initial?.enabled ?? true,
    };
    try {
      if (editing && initial) {
        await updateLlmModel(initial.id, input);
      } else {
        await createLlmModel(input);
      }
      await onSaved();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-overlay">
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <h3>{editing ? 'Edit model' : 'Add model'}</h3>
        <form className="modal-body" onSubmit={submit}>
          <div className="field-row-2">
            <label className="field">
              <span>Type</span>
              <Select
                ariaLabel="Type"
                value={identity.type}
                options={LLM_TYPES.map((t) => ({ value: t, label: t }))}
                onChange={(v) => updateIdentity({ type: v as LlmType })}
              />
            </label>
            <label className="field">
              <span>Model name</span>
              <input
                className="mono"
                placeholder="gpt-4o"
                value={identity.name}
                onChange={(e) => updateIdentity({ name: e.target.value })}
              />
            </label>
          </div>

          <label className="field">
            <span>
              Label <span className="field-optional">defaults to the model name</span>
            </span>
            <input placeholder="GPT-4o" value={identity.label} onChange={(e) => updateIdentity({ label: e.target.value })} />
          </label>

          <label className="field-check">
            <input
              type="checkbox"
              checked={pricingMode === 'UNMETERED'}
              onChange={(e) => setPricingMode(e.target.checked ? 'UNMETERED' : 'METERED')}
            />
            <span>Self-hosted — no per-token cost (UNMETERED)</span>
          </label>

          {pricingMode === 'METERED' && <ModelRateFields rates={rates} onChange={setRate} />}

          <SettingsLlmModelDialectFields
            type={identity.type}
            dialect={dialect}
            onDialectChange={updateDialect}
            extraParams={extraParams}
            onExtraParamsChange={setExtraParams}
          />

          {error && <div className="modal-msg modal-error">{error}</div>}

          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn" disabled={busy}>
              {busy ? 'Saving…' : editing ? 'Save changes' : 'Add model'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
