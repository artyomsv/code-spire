import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ServingCell from './ServingCell';
import * as api from '../api';

const repo: api.WebhookRepoView = {
  id: 'TEST-w1',
  providerType: 'github',
  scope: 'repo',
  target: 'TEST-acme/widgets',
  webhookKey: 'TEST-key',
  hasSecret: true,
  enabled: true,
  createdAt: '2026-09-07T00:00:00Z',
};

const lookupFor = (id: string, name: string) => ({
  data: {
    type: 'github',
    workspace: 'TEST-acme',
    reviewer: { state: 'ok' as api.ServingState, id, name, botUsername: null, botAccountId: null },
    factory: { state: 'missing' as api.ServingState, id: null, name: null, botUsername: null, botAccountId: null },
  } satisfies api.ServingAccounts,
});

describe('ServingCell', () => {
  beforeEach(() => vi.restoreAllMocks());

  /**
   * The row is never remounted — `<tr key={w.id}>` is stable — so without an explicit reset a
   * "reachable" line survives the account it was about and reads as a fact about the new one.
   */
  it('drops a verify result when the chip’s account changes', async () => {
    vi.spyOn(api, 'verifyRepo').mockResolvedValue({ ok: true, detail: null });
    const { rerender } = render(
      <ServingCell role="reviewer" lookup={lookupFor('TEST-first', 'first-bot')} repo={repo} />,
    );

    fireEvent.click(screen.getByRole('button', { name: /verify review account for TEST-acme\/widgets/i }));
    expect(await screen.findByText('reachable')).toBeInTheDocument();

    rerender(<ServingCell role="reviewer" lookup={lookupFor('TEST-second', 'second-bot')} repo={repo} />);
    await waitFor(() => expect(screen.getByText('second-bot')).toBeInTheDocument());
    expect(screen.queryByText('reachable')).not.toBeInTheDocument();
  });
});
