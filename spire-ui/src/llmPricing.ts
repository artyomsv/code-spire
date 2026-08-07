// Shared knowledge about the per-token-type rate dimensions, used by the model catalog form/table
// and the review cost card, so the token-type vocabulary is written once rather than three times.
import type { TokenType } from './api';
import { formatCost } from './money';

/** A rate dimension a model's catalog entry can carry — everything but the degraded TOTAL case. */
export type RateType = Exclude<TokenType, 'TOTAL'>;

/** Every rate dimension, in the order the model form and catalog table present them. */
export const RATE_TYPES: RateType[] = ['INPUT', 'CACHED_INPUT', 'CACHE_WRITE', 'OUTPUT', 'REASONING'];

/** Every vendor reports these two on every call; the rest are model-specific and may be omitted —
 *  a model that does not bill for them cannot be asked to price them. */
export const MANDATORY_RATE_TYPES: RateType[] = ['INPUT', 'OUTPUT'];

/** Display label for a token-billing dimension, shared by the model form's field labels, the
 *  catalog table's rate summary, and the review cost card's per-line breakdown. */
export const TOKEN_TYPE_LABEL: Record<TokenType, string> = {
  INPUT: 'Input',
  CACHED_INPUT: 'Cached input',
  CACHE_WRITE: 'Cache write',
  OUTPUT: 'Output',
  REASONING: 'Reasoning',
  TOTAL: 'Total (unreconciled)',
};

/** Sum of a model's per-type rates — used to sort the catalog most-expensive-first. An UNMETERED
 *  model's empty `rates` sums to 0, correctly sorting it last: it is the cheapest thing in the list. */
export function sumRates(rates: Partial<Record<RateType, number>>): number {
  return Object.values(rates).reduce((sum: number, v) => sum + (v ?? 0), 0);
}

/**
 * A per-1M-tokens rate (millicents) as a display string. Numerically identical to {@link formatCost}
 * — both are a millicents amount — but named for what it is: a rate is a price per million tokens,
 * not a review's spend, and reusing `formatCost`'s name at a rate call site read as a copy-paste slip.
 */
export const formatRate = formatCost;
