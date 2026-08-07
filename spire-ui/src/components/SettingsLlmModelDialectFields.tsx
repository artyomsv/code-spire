import type { LlmType, OutputTokenParam } from '../api';
import Select from './Select';

// Per-model API dialect (ADR-018): newer OpenAI reasoning models need
// max_completion_tokens instead of max_tokens and reject a custom temperature.
const TOKEN_PARAMS: { value: OutputTokenParam; label: string }[] = [
  { value: 'MAX_TOKENS', label: 'max_tokens · classic chat models' },
  { value: 'MAX_COMPLETION_TOKENS', label: 'max_completion_tokens · reasoning models' },
  { value: 'NONE', label: 'none · no output cap' },
];
const REASONING_EFFORTS = ['', 'low', 'medium', 'high'];

/** The model form's OpenAI-specific API dialect fields, grouped into one state slot in the parent
 *  form to stay under its own `useState` limit — they always change together (one form, one submit). */
export interface ApiDialect {
  outputTokenParam: OutputTokenParam;
  supportsTemperature: boolean;
  reasoningEffort: string;
}

interface Props {
  type: LlmType;
  dialect: ApiDialect;
  onDialectChange: (patch: Partial<ApiDialect>) => void;
  extraParams: string;
  onExtraParamsChange: (value: string) => void;
}

/**
 * The model form's "API parameters" section: output-cap dialect + reasoning effort (OpenAI-specific
 * — Anthropic/Gemini use a fixed native shape), the cross-vendor temperature toggle, and the raw
 * pass-through JSON textarea. Extracted purely for the parent form's line-count budget; it owns no
 * state of its own, just the fields the parent's `dialect`/`extraParams` state already carries.
 */
export default function SettingsLlmModelDialectFields({
  type,
  dialect,
  onDialectChange,
  extraParams,
  onExtraParamsChange,
}: Props) {
  return (
    <>
      <div className="field-sep">API parameters</div>

      {type === 'openai' && (
        <div className="field-row-2">
          <label className="field">
            <span>Output token limit</span>
            <Select
              ariaLabel="Output token limit"
              value={dialect.outputTokenParam}
              options={TOKEN_PARAMS.map((t) => ({ value: t.value, label: t.label }))}
              onChange={(v) => {
                const next = v as OutputTokenParam;
                // Reasoning models that require max_completion_tokens also reject a custom
                // temperature — preset the toggle so it's not a second thing to remember.
                onDialectChange(
                  next === 'MAX_COMPLETION_TOKENS'
                    ? { outputTokenParam: next, supportsTemperature: false }
                    : { outputTokenParam: next },
                );
              }}
            />
          </label>
          <label className="field">
            <span>
              Reasoning effort <span className="field-optional">optional</span>
            </span>
            <Select
              ariaLabel="Reasoning effort"
              value={dialect.reasoningEffort}
              options={REASONING_EFFORTS.map((r) => ({ value: r, label: r === '' ? '— none —' : r }))}
              onChange={(r) => onDialectChange({ reasoningEffort: r })}
            />
          </label>
        </div>
      )}

      {/* Temperature applies to every backend — some newer models (reasoning models, and newer
          Claude models like Fable) reject or deprecate it, so it must be togglable for all types. */}
      <label className="field-check">
        <input
          type="checkbox"
          checked={dialect.supportsTemperature}
          onChange={(e) => onDialectChange({ supportsTemperature: e.target.checked })}
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
            onChange={(e) => onExtraParamsChange(e.target.value)}
          />
          <small className="field-hint">
            Passed through verbatim to the model API — for parameters not covered above.
          </small>
        </label>
      )}
    </>
  );
}
