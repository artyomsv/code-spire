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

  /** The accessible name must carry the count, not just the fact -- a screen-reader user
   *  should not be told the opposite of what a sighted user sees on the badge. */
  it('gives the toggle button an accessible name that reflects the count', async () => {
    stubFeeds([], []);
    const { unmount } = renderBell();
    await waitFor(() =>
      expect(screen.getByTestId('attention-toggle')).toHaveAttribute(
        'aria-label',
        'Nothing needs attention',
      ),
    );
    unmount();

    stubFeeds([blocking], [warning]);
    renderBell();
    await waitFor(() =>
      expect(screen.getByTestId('attention-toggle')).toHaveAttribute(
        'aria-label',
        '2 conditions need attention',
      ),
    );
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

  /** The mirror case: the orchestrator's own feed can fail on its own (a DB blip) while the app
   *  is otherwise up. Losing its rows silently would render an empty panel — a claim of
   *  "all clear" the app never actually evaluated. */
  it('reports its own feed failing without losing the gateway rows', async () => {
    stubFeeds(new Error('pool exhausted'), [warning]);
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

  /**
   * Every row's link used to read the bare word "Settings", which told the operator nothing about
   * where they were about to land and left rows indistinguishable from each other — including to a
   * screen reader, which announces link text. Each destination must name itself.
   */
  it('names each link by where it goes, so two rows are distinguishable', async () => {
    const webhook: AttentionItem = {
      code: 'WEBHOOK_DELIVERIES_REJECTED',
      severity: 'WARNING',
      subject: 'stub · TEST-OWNER/TEST-REPO',
      message: '1 webhook delivery was refused — signature did not verify.',
      action: '/settings/webhooks',
    };
    stubFeeds([blocking], [webhook]);
    renderBell();
    await waitFor(() => screen.getByTestId('attention-count'));
    screen.getByTestId('attention-toggle').click();
    await waitFor(() => expect(screen.getByText(webhook.message)).toBeInTheDocument());

    expect(screen.getByRole('link', { name: 'Settings · LLM' })).toHaveAttribute(
      'href',
      '/settings/llm',
    );
    expect(screen.getByRole('link', { name: 'Settings · Webhooks' })).toHaveAttribute(
      'href',
      '/settings/webhooks',
    );
  });

  /**
   * A row naming one record deep-links to it with `?edit=<id>`. The label map is keyed on the path
   * alone, so the query string must not push the row onto the "Open" fallback — that would undo the
   * naming the label exists for.
   */
  it('keeps a named label when the action deep-links to a record', async () => {
    const deepLinked: AttentionItem = {
      code: 'WEBHOOK_DELIVERIES_REJECTED',
      severity: 'WARNING',
      subject: 'stub · TEST-OWNER/TEST-REPO',
      message: '1 webhook delivery was refused.',
      action: '/settings/webhooks?edit=TEST-id-1',
    };
    stubFeeds([], [deepLinked]);
    renderBell();
    await waitFor(() => screen.getByTestId('attention-count'));
    screen.getByTestId('attention-toggle').click();
    await waitFor(() => expect(screen.getByText(deepLinked.message)).toBeInTheDocument());

    expect(screen.getByRole('link', { name: 'Settings · Webhooks' })).toHaveAttribute(
      'href',
      '/settings/webhooks?edit=TEST-id-1',
    );
  });

  /** CREDENTIAL_REJECTED subjects are provider names with no cross-registry uniqueness — an SCM
   *  provider and an LLM provider can share a name and both be rejected. A React key that ignored
   *  `action` (the one field that differs across registries) collided and dropped a row. */
  it('renders every condition even when two share a code and subject', async () => {
    const scmRejected: AttentionItem = {
      code: 'CREDENTIAL_REJECTED',
      severity: 'WARNING',
      subject: 'prod',
      message: "The source-control provider's credential was rejected.",
      action: '/settings/providers',
    };
    const llmRejected: AttentionItem = {
      code: 'CREDENTIAL_REJECTED',
      severity: 'WARNING',
      subject: 'prod',
      message: "The LLM provider's credential was rejected.",
      action: '/settings/llm',
    };
    stubFeeds([scmRejected, llmRejected], []);
    renderBell();
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('2'));
    screen.getByTestId('attention-toggle').click();
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2));
  });
});
