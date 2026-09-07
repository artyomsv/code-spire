import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import * as api from '../api';
import { ownerOf, servingKey, useServingAccounts } from './useServingAccounts';

const repo = (over: Partial<api.WebhookRepoView>): api.WebhookRepoView => ({
  id: 'TEST-w1',
  providerType: 'github',
  scope: 'repo',
  target: 'TEST-acme/widgets',
  webhookKey: 'TEST-key',
  hasSecret: true,
  enabled: true,
  createdAt: '2026-09-07T00:00:00Z',
  ...over,
});

const serving = (workspace: string): api.ServingAccounts => ({
  type: 'github',
  workspace,
  reviewer: { state: 'ok', id: 'TEST-r', name: 'TEST reviewer', botUsername: 'r', botAccountId: 'TEST-a' },
  factory: { state: 'missing', id: null, name: null, botUsername: null, botAccountId: null },
});

function Probe({ repos }: { repos: api.WebhookRepoView[] }) {
  const lookups = useServingAccounts(repos);
  return <pre data-testid="lookups">{JSON.stringify(lookups)}</pre>;
}

describe('ownerOf', () => {
  it('is the owner segment for a repository and the whole target for an organization', () => {
    expect(ownerOf({ scope: 'repo', target: 'TEST-acme/widgets' })).toBe('TEST-acme');
    expect(ownerOf({ scope: 'org', target: 'TEST-acme' })).toBe('TEST-acme');
  });
});

describe('useServingAccounts', () => {
  beforeEach(() => vi.restoreAllMocks());

  /** Two rows in one workspace ask once: the answer is per (forge, workspace), not per repository. */
  it('asks once per distinct forge and owner', async () => {
    const fetch = vi.spyOn(api, 'fetchServingAccounts').mockImplementation(async (_t, ws) => serving(ws));
    const repos = [
      repo({ id: 'TEST-w1', target: 'TEST-acme/widgets' }),
      repo({ id: 'TEST-w2', target: 'TEST-acme/gadgets' }),
      repo({ id: 'TEST-w3', scope: 'org', target: 'TEST-other' }),
    ];
    render(<Probe repos={repos} />);

    await waitFor(() => expect(JSON.parse(screen.getByTestId('lookups').textContent ?? '{}')).toHaveProperty([servingKey('github', 'TEST-other')]));
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(fetch).toHaveBeenCalledWith('github', 'TEST-acme');
    expect(fetch).toHaveBeenCalledWith('github', 'TEST-other');
    const lookups = JSON.parse(screen.getByTestId('lookups').textContent ?? '{}');
    expect(lookups[servingKey('github', 'TEST-acme')].data.reviewer.state).toBe('ok');
  });

  /**
   * The effect's input is the SET of pairs, not the array carrying them. A caller that rebuilds its
   * array — a save, a delete, an inline literal — must not re-ask, and must not loop.
   */
  it('does not re-ask when the same rows arrive in a new array', async () => {
    const fetch = vi.spyOn(api, 'fetchServingAccounts').mockImplementation(async (_t, ws) => serving(ws));
    const repos = [
      repo({ id: 'TEST-w1', target: 'TEST-acme/widgets' }),
      repo({ id: 'TEST-w2', scope: 'org', target: 'TEST-other' }),
    ];
    const { rerender } = render(<Probe repos={repos} />);
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));

    rerender(<Probe repos={[...repos]} />);
    expect(fetch).toHaveBeenCalledTimes(2);

    // A genuinely new owner does change the set, so it is asked — once. Every pair is re-asked with
    // it, because clearing the answers is what makes the changed set honest.
    rerender(<Probe repos={[...repos, repo({ id: 'TEST-w3', target: 'TEST-third/thing' })]} />);
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('github', 'TEST-third'));
    expect(fetch.mock.calls.filter(([, ws]) => ws === 'TEST-third')).toHaveLength(1);
    expect(fetch).toHaveBeenCalledTimes(5);
  });

  it('keeps a failed lookup as an error, not as an empty answer', async () => {
    vi.spyOn(api, 'fetchServingAccounts').mockRejectedValue(new Error('Failed to load the accounts serving this workspace'));
    render(<Probe repos={[repo({})]} />);

    await waitFor(() => {
      const lookups = JSON.parse(screen.getByTestId('lookups').textContent ?? '{}');
      expect(lookups[servingKey('github', 'TEST-acme')].error).toMatch(/failed to load/i);
    });
  });
});
