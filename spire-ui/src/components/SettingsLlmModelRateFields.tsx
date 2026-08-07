import type { LlmModelView, PricingMode } from '../api';
import { dollarsToMillicentsPerMillion, millicentsPerMillionToDollars } from '../money';
import { MANDATORY_RATE_TYPES, RATE_TYPES, TOKEN_TYPE_LABEL, type RateType } from '../llmPricing';

export type EditablePricingMode = Exclude<PricingMode, 'UNKNOWN'>;

/** A rate field's placeholder — a plausible order of magnitude for that dimension, never a real
 *  vendor's actual current price (prices drift; see ADR-018 on why this is operator-entered). */
const RATE_PLACEHOLDER: Record<RateType, string> = {
  INPUT: '2.50',
  CACHED_INPUT: '0.30',
  CACHE_WRITE: '3.75',
  OUTPUT: '10.00',
  REASONING: '10.00',
};

/** Blank when absent, so a never-filled field round-trips as blank rather than as "$0". */
export function initialRates(initial: LlmModelView | null): Record<RateType, string> {
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
export function validateRates(mode: EditablePricingMode, rates: Record<RateType, string>): string | null {
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
export function ratesPayload(
  mode: EditablePricingMode,
  rates: Record<RateType, string>,
): Partial<Record<RateType, number>> {
  if (mode === 'UNMETERED') return {};
  const out: Partial<Record<RateType, number>> = {};
  for (const type of RATE_TYPES) {
    const raw = rates[type].trim();
    if (raw === '') continue;
    out[type] = dollarsToMillicentsPerMillion(Number(raw));
  }
  return out;
}

interface RateFieldProps {
  type: RateType;
  value: string;
  onChange: (value: string) => void;
}

/** One rate input, mapped over {@link RATE_TYPES} rather than hand-written per dimension — the label,
 *  the optional/mandatory marker and the placeholder all already come from shared, tested tables. */
function RateField({ type, value, onChange }: RateFieldProps) {
  const optional = !MANDATORY_RATE_TYPES.includes(type);
  return (
    <label className="field">
      <span>
        {TOKEN_TYPE_LABEL[type]} rate $ / 1M tokens {optional && <span className="field-optional">optional</span>}
      </span>
      <input
        className="mono"
        inputMode="decimal"
        placeholder={RATE_PLACEHOLDER[type]}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}

interface ModelRateFieldsProps {
  rates: Record<RateType, string>;
  onChange: (type: RateType, value: string) => void;
}

/** The model form's rate row — shown only under METERED (the parent hides this entirely when the
 *  model is marked self-hosted, rather than rendering disabled inputs nobody can fill in anyway). */
export default function ModelRateFields({ rates, onChange }: ModelRateFieldsProps) {
  return (
    <>
      <div className="field-row-2">
        {RATE_TYPES.map((type) => (
          <RateField key={type} type={type} value={rates[type]} onChange={(v) => onChange(type, v)} />
        ))}
      </div>
      <small className="field-hint">
        Enter the provider's current published price per 1M tokens for each dimension it bills — used
        to cost each review. Leave a dimension blank if the model doesn't bill for it separately.
      </small>
    </>
  );
}
