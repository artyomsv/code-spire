import { describe, expect, it } from 'vitest';
import { parsePrNumber } from './RegisterPrDialog';

/**
 * The PR # field used to be only non-empty-checked; `Number('abc')` is NaN,
 * which JSON-serializes to null and breaks the backend register call.
 */
describe('parsePrNumber', () => {
  it('parses positive integers, tolerating surrounding whitespace', () => {
    expect(parsePrNumber('123')).toBe(123);
    expect(parsePrNumber(' 42 ')).toBe(42);
    expect(parsePrNumber('1')).toBe(1);
  });

  it('rejects non-numeric and partially numeric input', () => {
    expect(parsePrNumber('abc')).toBeNull();
    expect(parsePrNumber('12a')).toBeNull();
    expect(parsePrNumber('1.5')).toBeNull();
    expect(parsePrNumber('1e3')).toBeNull();
    expect(parsePrNumber('')).toBeNull();
    expect(parsePrNumber('   ')).toBeNull();
  });

  it('rejects zero, negatives and unsafe magnitudes', () => {
    expect(parsePrNumber('0')).toBeNull();
    expect(parsePrNumber('-1')).toBeNull();
    expect(parsePrNumber('9007199254740993')).toBeNull(); // > Number.MAX_SAFE_INTEGER
  });
});
