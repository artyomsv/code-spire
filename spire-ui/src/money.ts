// Money helpers. All amounts are millicents (1/100,000 dollar) per the money rule.

/**
 * A cost (millicents) as a display string.
 *
 * `null` and a zero are deliberately different: null means the call could not be priced, while zero
 * is an operator's assertion that an UNMETERED model costs nothing to call. Collapsing them — as this
 * function used to, treating `0` as "unpriced" — is the conflation the charge ledger was built to
 * remove.
 */
export function formatCost(millicents: number | null): string {
  if (millicents === null || millicents === undefined) {
    return '—';
  }
  return `$${(millicents / 100_000).toFixed(3)}`;
}

/** Model price: dollars per 1M tokens -> millicents per 1M tokens (integer storage). */
export function dollarsToMillicentsPerMillion(dollars: number): number {
  return Math.round(dollars * 100_000);
}

/** Model price: millicents per 1M tokens -> dollars per 1M tokens (for display/editing). */
export function millicentsPerMillionToDollars(millicents: number): number {
  return millicents / 100_000;
}
