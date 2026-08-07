import { describe, expect, it } from 'vitest';
import { dollarsToMillicentsPerMillion, formatCost, millicentsPerMillionToDollars } from './money';

describe('formatCost', () => {
  it('renders an asserted zero as a zero, and only an absent cost as a dash', () => {
    // The conflation the charge ledger removes: 0 is an UNMETERED operator's assertion that a call
    // was free, while null means it could not be priced. A dash for both told a self-hosted
    // operator their cost was unknown when it was known to be nothing.
    expect(formatCost(0)).toBe('$0.000');
    expect(formatCost(null)).toBe('—');
  });
  it('formats millicents as dollars', () => {
    expect(formatCost(2500)).toBe('$0.025'); // 2500 mc = $0.025
    expect(formatCost(250_000)).toBe('$2.500');
  });
});

describe('model price conversion', () => {
  it('round-trips gpt-4o input pricing ($2.50 / 1M)', () => {
    expect(dollarsToMillicentsPerMillion(2.5)).toBe(250_000);
    expect(millicentsPerMillionToDollars(250_000)).toBe(2.5);
  });
  it('round-trips cheap pricing ($0.15 / 1M)', () => {
    expect(dollarsToMillicentsPerMillion(0.15)).toBe(15_000);
    expect(millicentsPerMillionToDollars(15_000)).toBe(0.15);
  });
});
