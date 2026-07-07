// Money helpers. All amounts are millicents (1/100,000 dollar) per the money rule.

/** A review cost (millicents) as a display string; em-dash when unpriced (0). */
export function formatCost(millicents: number): string {
  if (!millicents || millicents <= 0) {
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
