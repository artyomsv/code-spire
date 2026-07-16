import { describe, expect, it } from 'vitest';
import { conversationLabel } from './SettingsProviders';

describe('conversationLabel', () => {
  it('maps each level to its label', () => {
    expect(conversationLabel('REPORT_ONLY')).toBe('Report-only');
    expect(conversationLabel('EXPLAIN')).toBe('Explain');
    expect(conversationLabel('INTERACTIVE')).toBe('Interactive');
  });

  it('treats blank, null, and unknown as inherit', () => {
    expect(conversationLabel('')).toBe('Inherit (global)');
    expect(conversationLabel(null)).toBe('Inherit (global)');
    expect(conversationLabel(undefined)).toBe('Inherit (global)');
    expect(conversationLabel('bogus')).toBe('Inherit (global)');
  });
});
