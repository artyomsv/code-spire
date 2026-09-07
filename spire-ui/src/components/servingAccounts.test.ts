import { describe, expect, it } from 'vitest';
import type { ServingAccount } from '../api';
import { servingChip } from './servingAccounts';

const account = (state: ServingAccount['state'], name = 'TEST-bot'): ServingAccount => ({
  state,
  id: 'TEST-id',
  name,
  botUsername: 'test-bot',
  botAccountId: 'TEST-acct',
});

describe('servingChip', () => {
  it('is green only for ok', () => {
    expect(servingChip(account('ok'), null)).toMatchObject({ label: 'TEST-bot', pill: 'completed' });
  });

  it('is amber for the two half-registered states, and names the account', () => {
    expect(servingChip(account('no-identity'), null)).toMatchObject({ label: 'TEST-bot', pill: 'refused' });
    expect(servingChip(account('no-login'), null)).toMatchObject({ label: 'TEST-bot', pill: 'refused' });
  });

  it('is grey for disabled and for missing', () => {
    expect(servingChip(account('disabled'), null)).toMatchObject({ label: 'TEST-bot', pill: 'cancelled' });
    expect(servingChip(account('missing', ''), null)).toMatchObject({ label: 'none', pill: 'cancelled' });
  });

  /**
   * The server's state set can grow before this bundle does. A value the union does not list must
   * render grey and say so — never green, which is what a `refused` review once rendered as.
   */
  it('renders an unlisted state as unknown, never as ok', () => {
    const chip = servingChip({ ...account('ok'), state: 'brand-new' as ServingAccount['state'] }, null);
    expect(chip.pill).toBe('cancelled');
    expect(chip.label).toBe('unknown (brand-new)');
  });

  it('renders a failed lookup as unknown with the error as its title', () => {
    const chip = servingChip(undefined, 'Failed to load');
    expect(chip).toMatchObject({ label: 'unknown', pill: 'cancelled', title: 'Failed to load' });
  });

  it('renders a lookup still in flight as loading', () => {
    expect(servingChip(undefined, null).label).toBe('loading…');
  });
});
