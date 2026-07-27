import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AttentionBell from './AttentionBell';
import type { AttentionItem } from '../api';

const blocking: AttentionItem = {
  code: 'LLM_DEFAULT_MISSING',
  severity: 'BLOCKING',
  subject: null,
  message: 'No enabled LLM provider is marked as the default, so no review can run.',
  action: '/settings/llm',
};

const warning: AttentionItem = {
  code: 'DLQ_PENDING',
  severity: 'WARNING',
  subject: null,
  message: '2 message(s) failed processing and are waiting in the dead-letter queue.',
  action: '/settings/dlq',
};

/** Serve the orchestrator feed and the gateway feed independently, as the hook fetches them. */
function stubFeeds(orchestrator: AttentionItem[] | Error, gateway: AttentionItem[] | Error) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      const body = url.includes('webhook-repos') ? gateway : orchestrator;
      if (body instanceof Error) return Promise.reject(body);
      return Promise.resolve({ ok: true, json: () => Promise.resolve(body) } as Response);
    }),
  );
}

const renderBell = () =>
  render(
    <MemoryRouter>
      <AttentionBell />
    </MemoryRouter>,
  );

describe('AttentionBell', () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('counts every condition from both feeds', async () => {
    stubFeeds([blocking], [warning]);
    renderBell();
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('2'));
  });

  /** A green tick would be a claim the panel cannot make: it only knows what it checks. */
  it('renders no badge when nothing needs attention', async () => {
    stubFeeds([], []);
    renderBell();
    await waitFor(() => expect(screen.queryByTestId('attention-count')).toBeNull());
  });

  it('takes its colour from the most severe condition present', async () => {
    stubFeeds([blocking], [warning]);
    renderBell();
    await waitFor(() =>
      expect(screen.getByTestId('attention-count').className).toContain('blocking'),
    );
  });

  it('is a warning when no blocker is present', async () => {
    stubFeeds([warning], []);
    renderBell();
    await waitFor(() =>
      expect(screen.getByTestId('attention-count').className).toContain('warning'),
    );
  });

  /** An unreachable gateway means no webhook is arriving at all — strictly blocking. */
  it('reports an unreachable gateway without losing the other feed', async () => {
    stubFeeds([warning], new Error('connection refused'));
    renderBell();
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('2'));
    expect(screen.getByTestId('attention-count').className).toContain('blocking');
  });

  it('lists each condition with a link to the page that fixes it', async () => {
    stubFeeds([blocking], []);
    renderBell();
    await waitFor(() => screen.getByTestId('attention-count'));
    screen.getByTestId('attention-toggle').click();
    await waitFor(() => expect(screen.getByText(blocking.message)).toBeInTheDocument());
    expect(screen.getByRole('link', { name: /settings/i })).toHaveAttribute('href', '/settings/llm');
  });
});
