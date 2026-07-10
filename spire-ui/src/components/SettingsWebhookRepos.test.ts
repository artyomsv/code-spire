import { describe, expect, it } from 'vitest';
import { webhookPath } from './SettingsWebhookRepos';

describe('webhookPath', () => {
  it('routes by provider type and key', () => {
    expect(webhookPath({ providerType: 'github', webhookKey: 'abc123' })).toBe('/webhooks/github/abc123');
  });

  it('keeps the key verbatim (URL-safe by construction)', () => {
    expect(webhookPath({ providerType: 'github', webhookKey: 'aB_9-xY' })).toBe('/webhooks/github/aB_9-xY');
  });
});
