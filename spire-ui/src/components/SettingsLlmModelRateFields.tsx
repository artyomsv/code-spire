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

/** Which types the operator has asserted the vendor does not bill. Any type may carry one. */
export function initialNotBilled(initial: LlmModelView | null): Record<RateType, boolean> {
  const out = {} as Record<RateType, boolean>;
  for (const type of RATE_TYPES) out[type] = !!initial?.notBilled?.includes(type);
  return out;
}

/** The assertion as the API takes it: only the optional types can carry one. */
export function notBilledPayload(mode: EditablePricingMode, notBilled: Record<RateType, boolean>): RateType[] {
  if (mode === 'UNMETERED') return [];
  return RATE_TYPES.filter(type => notBilled[type]);
}

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
export function validateRates(mode: EditablePricingMode, rates: Record<RateType, string>,
                              notBilled: Record<RateType, boolean> = {} as Record<RateType, boolean>): string | null {
  if (mode === 'UNMETERED') return null;
  for (const type of RATE_TYPES) {
    const raw = rates[type].trim();
    const mandatory = MANDATORY_RATE_TYPES.includes(type);
    if (notBilled[type]) {
      if (raw !== '') return `${TOKEN_TYPE_LABEL[type]} has both a rate and "not billed". Keep the one that is true.`;
      continue; // an assertion counts as said, for any type: some vendors bill nothing for one of them
    }
    if (raw === '') {
      if (mandatory) return `${TOKEN_TYPE_LABEL[type]} needs a rate, or the mark saying this vendor does not bill it.`;
      continue; // left blank: nobody has said what it costs, so a call reporting it stays unpriced
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
  notBilled: boolean;
  onNotBilled: (value: boolean) => void;
}

/** One rate input, mapped over {@link RATE_TYPES} rather than hand-written per dimension — the label,
 *  the optional/mandatory marker and the placeholder all already come from shared, tested tables. */
function RateField({ type, value, onChange, notBilled, onNotBilled }: RateFieldProps) {
  const optional = !MANDATORY_RATE_TYPES.includes(type);
  return (
    <div className="field">
      <label className="field">
        <span>
          {TOKEN_TYPE_LABEL[type]} rate $ / 1M tokens {optional && <span className="field-optional">optional</span>}
        </span>
        <input
          className="mono"
          inputMode="decimal"
          placeholder={RATE_PLACEHOLDER[type]}
          value={notBilled ? '' : value}
          disabled={notBilled}
          onChange={(e) => onChange(e.target.value)}
        />
      </label>
      {/* An assertion, not a zero: the operator says this vendor charges nothing for this type. A run
          that reports a type with neither a rate nor this box stops the item with an unknown cost. */}
      {(
        <label className="field-check">
          <input
            type="checkbox"
            aria-label={`${TOKEN_TYPE_LABEL[type]}: the vendor does not bill this`}
            checked={notBilled}
            onChange={(e) => onNotBilled(e.target.checked)}
          />
          <span>The vendor does not bill this</span>
        </label>
      )}
    </div>
  );
}

interface ModelRateFieldsProps {
  rates: Record<RateType, string>;
  onChange: (type: RateType, value: string) => void;
  notBilled: Record<RateType, boolean>;
  onNotBilled: (type: RateType, value: boolean) => void;
}

/** The model form's rate row — shown only under METERED (the parent hides this entirely when the
 *  model is marked self-hosted, rather than rendering disabled inputs nobody can fill in anyway). */
export default function ModelRateFields({ rates, onChange, notBilled, onNotBilled }: ModelRateFieldsProps) {
  return (
    <>
      <div className="field-row-2">
        {RATE_TYPES.map((type) => (
          <RateField key={type} type={type} value={rates[type]} onChange={(v) => onChange(type, v)}
            notBilled={!!notBilled[type]} onNotBilled={(v) => onNotBilled(type, v)} />
        ))}
      </div>
      <small className="field-hint">
        Enter the provider's current published price per 1M tokens for each dimension it bills — used to
        cost each run. A dimension left blank means nobody has said what it costs, and a run that reports
        it stops with an unknown cost. Tick "the vendor does not bill this" to say so on purpose; input
        and output need one or the other before the model can be saved.
      </small>
    </>
  );
}
