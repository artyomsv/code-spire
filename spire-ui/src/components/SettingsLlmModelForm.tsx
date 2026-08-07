import { useState } from 'react';
import {
  createLlmModel,
  updateLlmModel,
  type LlmModelInput,
  type LlmModelView,
  type LlmType,
  type OutputTokenParam,
  type PricingMode,
} from '../api';
import { dollarsToMillicentsPerMillion, millicentsPerMillionToDollars } from '../money';
import { MANDATORY_RATE_TYPES, RATE_TYPES, TOKEN_TYPE_LABEL, type RateType } from '../llmPricing';
import Select from './Select';

// Phase 1: OpenAI only. Anthropic/Gemini land in phase 2. Duplicated from SettingsLlmProviders
// (which owns the provider form's identical Type select) rather than shared, to keep this file
// self-contained after the extraction — both lists are a single line and change together anyway.
const LLM_TYPES: LlmType[] = ['openai', 'anthropic', 'gemini'];

// Per-model API dialect (ADR-018): newer OpenAI reasoning models need
// max_completion_tokens instead of max_tokens and reject a custom temperature.
const TOKEN_PARAMS: { value: OutputTokenParam; label: string }[] = [
  { value: 'MAX_TOKENS', label: 'max_tokens · classic chat models' },
  { value: 'MAX_COMPLETION_TOKENS', label: 'max_completion_tokens · reasoning models' },
  { value: 'NONE', label: 'none · no output cap' },
];
const REASONING_EFFORTS = ['', 'low', 'medium', 'high'];

type EditablePricingMode = Exclude<PricingMode, 'UNKNOWN'>;

/** Blank when absent, so a never-filled field round-trips as blank rather than as "$0". */
function initialRates(initial: LlmModelView | null): Record<RateType, string> {
  const out = {} as Record<RateType, string>;
  for (const type of RATE_TYPES) {
    const mc = initial?.rates[type];
    out[type] = mc != null ? String(millicentsPerMillionToDollars(mc)) : '';
  }
  return out;
}

/**
 * Validates the rate fields for the pricing mode chosen. A blank mandatory field or a non-positive
 * rate is an error, not a default — `Number('') || 0` is exactly the bug this form exists to remove.
 * Returns the first violation found, or null when the fields are saveable.
 */
function validateRates(mode: EditablePricingMode, rates: Record<RateType, string>): string | null {
  if (mode === 'UNMETERED') return null;
  for (const type of RATE_TYPES) {
    const raw = rates[type].trim();
    const mandatory = MANDATORY_RATE_TYPES.includes(type);
    if (raw === '') {
      if (mandatory) return `${TOKEN_TYPE_LABEL[type]} rate is required for a metered model.`;
      continue; // optional dimension left blank — simply not billed
    }
    if (!(Number(raw) > 0)) return `${TOKEN_TYPE_LABEL[type]} rate must be greater than zero.`;
  }
  return null;
}

/** Only the filled-in fields become rates — a blank one is an ABSENT rate, never a zero one. */
function ratesPayload(mode: EditablePricingMode, rates: Record<RateType, string>): Partial<Record<RateType, number>> {
  if (mode === 'UNMETERED') return {};
  const out: Partial<Record<RateType, number>> = {};
  for (const type of RATE_TYPES) {
    const raw = rates[type].trim();
    if (raw === '') continue;
    out[type] = dollarsToMillicentsPerMillion(Number(raw));
  }
  return out;
}

export default function SettingsLlmModelForm({
  initial,
  onClose,
  onSaved,
}: {
  initial: LlmModelView | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const editing = initial !== null;
  const [type, setType] = useState<LlmType>(initial?.type ?? 'openai');
  const [name, setName] = useState(initial?.name ?? '');
  const [label, setLabel] = useState(initial?.label ?? '');
  const [pricingMode, setPricingMode] = useState<EditablePricingMode>(initial?.pricingMode ?? 'METERED');
  const [rates, setRates] = useState<Record<RateType, string>>(() => initialRates(initial));
  const [outputTokenParam, setOutputTokenParam] = useState<OutputTokenParam>(
    initial?.outputTokenParam ?? 'MAX_TOKENS',
  );
  const [supportsTemperature, setSupportsTemperature] = useState(initial?.supportsTemperature ?? true);
  const [reasoningEffort, setReasoningEffort] = useState(initial?.reasoningEffort ?? '');
  const [extraParams, setExtraParams] = useState(
    initial && initial.extraParams && Object.keys(initial.extraParams).length > 0
      ? JSON.stringify(initial.extraParams, null, 2)
      : '',
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function setRate(type: RateType, value: string) {
    setRates((prev) => ({ ...prev, [type]: value }));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const rateError = validateRates(pricingMode, rates);
    if (rateError) {
      setError(rateError);
      return;
    }
    let parsedExtra: Record<string, unknown> = {};
    if (extraParams.trim()) {
      let value: unknown;
      try {
        value = JSON.parse(extraParams);
      } catch {
        setError('Extra params must be valid JSON.');
        return;
      }
      if (typeof value !== 'object' || value === null || Array.isArray(value)) {
        setError('Extra params must be a JSON object, e.g. {"service_tier": "flex"}.');
        return;
      }
      parsedExtra = value as Record<string, unknown>;
    }
    setBusy(true);
    setError(null);
    const input: LlmModelInput = {
      type,
      name: name.trim(),
      label: label.trim() || name.trim(),
      pricingMode,
      rates: ratesPayload(pricingMode, rates),
      outputTokenParam,
      supportsTemperature,
      reasoningEffort: reasoningEffort.trim() || null,
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
                value={type}
                options={LLM_TYPES.map((t) => ({ value: t, label: t }))}
                onChange={(v) => setType(v as LlmType)}
              />
            </label>
            <label className="field">
              <span>Model name</span>
              <input className="mono" placeholder="gpt-4o" value={name} onChange={(e) => setName(e.target.value)} />
            </label>
          </div>

          <label className="field">
            <span>
              Label <span className="field-optional">defaults to the model name</span>
            </span>
            <input placeholder="GPT-4o" value={label} onChange={(e) => setLabel(e.target.value)} />
          </label>

          <label className="field-check">
            <input
              type="checkbox"
              checked={pricingMode === 'UNMETERED'}
              onChange={(e) => setPricingMode(e.target.checked ? 'UNMETERED' : 'METERED')}
            />
            <span>Self-hosted — no per-token cost (UNMETERED)</span>
          </label>

          {pricingMode === 'METERED' && (
            <>
              <div className="field-row-2">
                <label className="field">
                  <span>Input rate $ / 1M tokens</span>
                  <input
                    className="mono"
                    inputMode="decimal"
                    placeholder="2.50"
                    value={rates.INPUT}
                    onChange={(e) => setRate('INPUT', e.target.value)}
                  />
                </label>
                <label className="field">
                  <span>Output rate $ / 1M tokens</span>
                  <input
                    className="mono"
                    inputMode="decimal"
                    placeholder="10.00"
                    value={rates.OUTPUT}
                    onChange={(e) => setRate('OUTPUT', e.target.value)}
                  />
                </label>
              </div>
              <div className="field-row-2">
                <label className="field">
                  <span>
                    Cached input rate $ / 1M tokens <span className="field-optional">optional</span>
                  </span>
                  <input
                    className="mono"
                    inputMode="decimal"
                    placeholder="0.30"
                    value={rates.CACHED_INPUT}
                    onChange={(e) => setRate('CACHED_INPUT', e.target.value)}
                  />
                </label>
                <label className="field">
                  <span>
                    Cache write rate $ / 1M tokens <span className="field-optional">optional</span>
                  </span>
                  <input
                    className="mono"
                    inputMode="decimal"
                    placeholder="3.75"
                    value={rates.CACHE_WRITE}
                    onChange={(e) => setRate('CACHE_WRITE', e.target.value)}
                  />
                </label>
              </div>
              <label className="field">
                <span>
                  Reasoning rate $ / 1M tokens <span className="field-optional">optional</span>
                </span>
                <input
                  className="mono"
                  inputMode="decimal"
                  placeholder="10.00"
                  value={rates.REASONING}
                  onChange={(e) => setRate('REASONING', e.target.value)}
                />
              </label>
              <small className="field-hint">
                Enter the provider's current published price per 1M tokens for each dimension it
                bills — used to cost each review. Leave a dimension blank if the model doesn't bill
                for it separately.
              </small>
            </>
          )}

          <div className="field-sep">API parameters</div>

          {/* Output-cap dialect + reasoning effort are OpenAI-specific; Anthropic/Gemini use a fixed
              native shape (temperature + a single output cap). */}
          {type === 'openai' && (
            <div className="field-row-2">
              <label className="field">
                <span>Output token limit</span>
                <Select
                  ariaLabel="Output token limit"
                  value={outputTokenParam}
                  options={TOKEN_PARAMS.map((t) => ({ value: t.value, label: t.label }))}
                  onChange={(v) => {
                    const next = v as OutputTokenParam;
                    setOutputTokenParam(next);
                    // Reasoning models that require max_completion_tokens also reject a custom
                    // temperature — preset the toggle so it's not a second thing to remember.
                    if (next === 'MAX_COMPLETION_TOKENS') setSupportsTemperature(false);
                  }}
                />
              </label>
              <label className="field">
                <span>
                  Reasoning effort <span className="field-optional">optional</span>
                </span>
                <Select
                  ariaLabel="Reasoning effort"
                  value={reasoningEffort}
                  options={REASONING_EFFORTS.map((r) => ({ value: r, label: r === '' ? '— none —' : r }))}
                  onChange={setReasoningEffort}
                />
              </label>
            </div>
          )}

          {/* Temperature applies to every backend — some newer models (reasoning models, and newer
              Claude models like Fable) reject or deprecate it, so it must be togglable for all types. */}
          <label className="field-check">
            <input
              type="checkbox"
              checked={supportsTemperature}
              onChange={(e) => setSupportsTemperature(e.target.checked)}
            />
            <span>Model accepts a custom temperature (uncheck if the model rejects or deprecates it)</span>
          </label>

          {type === 'openai' && (
            <label className="field">
              <span>
                Extra params <span className="field-optional">advanced · JSON</span>
              </span>
              <textarea
                className="mono"
                rows={3}
                placeholder={'{ "service_tier": "flex" }'}
                value={extraParams}
                onChange={(e) => setExtraParams(e.target.value)}
              />
              <small className="field-hint">
                Passed through verbatim to the model API — for parameters not covered above.
              </small>
            </label>
          )}

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
