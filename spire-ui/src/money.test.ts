import { describe, expect, it } from 'vitest';
import { dollarsToMillicentsPerMillion, formatCost, millicentsPerMillionToDollars } from './money';

describe('formatCost', () => {
  it('em-dashes an unpriced review', () => {
    expect(formatCost(0)).toBe('—');
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
