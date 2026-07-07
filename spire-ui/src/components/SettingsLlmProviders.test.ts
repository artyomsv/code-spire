import { describe, expect, it } from 'vitest';
import { defaultBaseUrl } from './SettingsLlmProviders';

describe('defaultBaseUrl', () => {
  it('returns the OpenAI base URL for openai', () => {
    expect(defaultBaseUrl('openai')).toBe('https://api.openai.com/v1');
  });

  it('returns empty for an unknown type', () => {
    expect(defaultBaseUrl('anthropic')).toBe('');
    expect(defaultBaseUrl('')).toBe('');
  });
});
