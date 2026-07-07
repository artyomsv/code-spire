import { describe, expect, it } from 'vitest';
import { normalizeMode } from './ReviewModeToggle';

describe('normalizeMode', () => {
  it('accepts the two valid modes', () => {
    expect(normalizeMode('observe')).toBe('observe');
    expect(normalizeMode('active')).toBe('active');
  });

  it('is case-insensitive and trims', () => {
    expect(normalizeMode('  OBSERVE  ')).toBe('observe');
    expect(normalizeMode('Active')).toBe('active');
  });

  it('treats anything unknown as active (matches the backend default)', () => {
    expect(normalizeMode('')).toBe('active');
    expect(normalizeMode('bogus')).toBe('active');
    expect(normalizeMode(null)).toBe('active');
    expect(normalizeMode(undefined)).toBe('active');
  });
});
