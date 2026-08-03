import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import AttentionBell from './AttentionBell';
import type { AttentionItem } from '../api';

const blocking: AttentionItem = {
  code: 'LLM_DEFAULT_MISSING',
  severity: 'BLOCKING',
  subject: null,
  message: 'No enabled LLM provider is marked as the default, so no review can run.',
  action: '/settings/llm',
  dismiss: null,
};

const warning: AttentionItem = {
  code: 'DLQ_PENDING',
  severity: 'WARNING',
  subject: null,
  message: '2 message(s) failed processing and are waiting in the dead-letter queue.',
  action: '/settings/dlq',
  dismiss: null,
};

const failed: AttentionItem = {
  code: 'REVIEW_FAILED',
  severity: 'WARNING',
  subject: 'TEST-WS/TEST-REPO#1',
  message: 'This review failed.',
  action: '/r/TEST-WS/TEST-REPO/1',
  dismiss: '/api/reviews/TEST-WS/TEST-REPO/1/attention-ack',
};

/**
 * A stand-in for the browser's WebSocket, keyed by the path it was opened on, so a test can push a
 * frame down one feed and leave the other alone — the merge behaviour depends on the two being
 * independent.
 */
class FakeSocket {
  static open = new Map<string, FakeSocket>();

  onmessage: ((ev: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(public url: string) {
    FakeSocket.open.set(FakeSocket.pathOf(url), this);
  }

  close() {
    this.closed = true;
    this.onclose?.();
  }

  static pathOf(url: string): string {
    return new URL(url).pathname;
  }

  /** Deliver a condition list, as the server does on connect and on every change. */
  static push(path: string, rows: AttentionItem[]) {
    FakeSocket.open.get(path)?.onmessage?.({ data: JSON.stringify(rows) });
  }

  static drop(path: string) {
    FakeSocket.open.get(path)?.onclose?.();
  }
}

const ORCHESTRATOR = '/api/ws/attention';
const GATEWAY = '/gw/ws/webhook-attention';

const renderBell = () =>
  render(
    <MemoryRouter>
      <AttentionBell />
    </MemoryRouter>,
  );

/** Both feeds connected and reporting, which is the precondition for most assertions below. */
async function renderWithFeeds(orchestrator: AttentionItem[], gateway: AttentionItem[]) {
  renderBell();
  await waitFor(() => expect(FakeSocket.open.size).toBe(2));
  FakeSocket.push(ORCHESTRATOR, orchestrator);
  FakeSocket.push(GATEWAY, gateway);
}

describe('AttentionBell', () => {
  beforeEach(() => {
    FakeSocket.open.clear();
    vi.stubGlobal('WebSocket', FakeSocket);
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: true } as Response)));
  });
  afterEach(() => vi.unstubAllGlobals());

  /**
   * A socket that has not answered yet is not a socket that has failed. Treating the two alike made
   * every page load flash "2" in blocking red — one synthesized row per feed — until the sockets
   * opened, and would have shown it indefinitely had a service been slow to accept. Nothing has been
   * established before the first frame, so the panel must claim nothing.
   */
  it('shows no badge while the sockets are still connecting', async () => {
    renderBell();
    await waitFor(() => expect(FakeSocket.open.size).toBe(2));

    // Both sockets open, neither has delivered a frame — the exact moment that used to show a
    // false blocking alarm.
    expect(screen.queryByTestId('attention-count')).toBeNull();
    expect(screen.getByTestId('attention-toggle')).toHaveAttribute(
      'aria-label',
      'Nothing needs attention',
    );
  });

  /** But a socket that genuinely fails before ever delivering must still raise its row. */
  it('reports a feed that fails before its first frame', async () => {
    renderBell();
    await waitFor(() => expect(FakeSocket.open.size).toBe(2));
    FakeSocket.push(ORCHESTRATOR, []);

    FakeSocket.drop(GATEWAY);

    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('1'));
    expect(screen.getByTestId('attention-count').className).toContain('blocking');
  });

  /** Pushed, not polled: no request is made to read conditions. */
  it('opens a socket per service and fetches nothing to read them', async () => {
    await renderWithFeeds([blocking], [warning]);
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('2'));
    expect(FakeSocket.open.has(ORCHESTRATOR)).toBe(true);
    expect(FakeSocket.open.has(GATEWAY)).toBe(true);
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  /** A later frame replaces that feed's rows, which is how a fixed condition disappears. */
  it('replaces a feed’s rows when a new list arrives', async () => {
    await renderWithFeeds([blocking], []);
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('1'));

    FakeSocket.push(ORCHESTRATOR, []);

    await waitFor(() => expect(screen.queryByTestId('attention-count')).toBeNull());
  });

  /** A green tick would be a claim the panel cannot make: it only knows what it checks. */
  it('renders no badge when nothing needs attention', async () => {
    await renderWithFeeds([], []);
    await waitFor(() => expect(screen.queryByTestId('attention-count')).toBeNull());
  });

  it('gives the toggle button an accessible name that reflects the count', async () => {
    await renderWithFeeds([], []);
    await waitFor(() =>
      expect(screen.getByTestId('attention-toggle')).toHaveAttribute(
        'aria-label',
        'Nothing needs attention',
      ),
    );

    FakeSocket.push(ORCHESTRATOR, [blocking, warning]);

    await waitFor(() =>
      expect(screen.getByTestId('attention-toggle')).toHaveAttribute(
        'aria-label',
        '2 conditions need attention',
      ),
    );
  });

  it('takes its colour from the most severe condition present', async () => {
    await renderWithFeeds([blocking], [warning]);
    await waitFor(() =>
      expect(screen.getByTestId('attention-count').className).toContain('blocking'),
    );
  });

  it('is a warning when no blocker is present', async () => {
    await renderWithFeeds([warning], []);
    await waitFor(() =>
      expect(screen.getByTestId('attention-count').className).toContain('warning'),
    );
  });

  /**
   * A dropped socket is not silence. Contributing nothing would render an empty panel — a claim of
   * "all clear" about conditions nobody evaluated — so the feed's absence becomes its own row while
   * the other feed keeps reporting.
   */
  it('reports a dropped gateway socket without losing the other feed', async () => {
    await renderWithFeeds([warning], []);
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('1'));

    FakeSocket.drop(GATEWAY);

    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('2'));
    expect(screen.getByTestId('attention-count').className).toContain('blocking');
  });

  /** The mirror case, which is the one an earlier version of this panel got wrong. */
  it('reports a dropped orchestrator socket without losing the gateway rows', async () => {
    await renderWithFeeds([], [warning]);
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('1'));

    FakeSocket.drop(ORCHESTRATOR);

    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('2'));
    expect(screen.getByTestId('attention-count').className).toContain('blocking');
  });

  /** A malformed frame must not blank a feed that was working. */
  it('ignores an unparseable frame', async () => {
    await renderWithFeeds([blocking], []);
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('1'));

    FakeSocket.open.get(ORCHESTRATOR)?.onmessage?.({ data: 'not json' });

    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('1'));
  });

  it('names each link by where it goes, so two rows are distinguishable', async () => {
    const webhook: AttentionItem = {
      code: 'WEBHOOK_DELIVERIES_REJECTED',
      severity: 'WARNING',
      subject: 'stub · TEST-OWNER/TEST-REPO',
      message: '1 webhook delivery was refused.',
      action: '/settings/webhooks?edit=TEST-id-1',
      dismiss: null,
    };
    await renderWithFeeds([blocking], [webhook]);
    await waitFor(() => screen.getByTestId('attention-count'));
    screen.getByTestId('attention-toggle').click();
    await waitFor(() => expect(screen.getByText(webhook.message)).toBeInTheDocument());

    // The label map is keyed on the path alone, so `?edit=` must not push the row onto "Open".
    expect(screen.getByRole('link', { name: 'Settings · Webhooks' })).toHaveAttribute(
      'href',
      '/settings/webhooks?edit=TEST-id-1',
    );
    expect(screen.getByRole('link', { name: 'Settings · LLM' })).toHaveAttribute(
      'href',
      '/settings/llm',
    );
  });

  /** CREDENTIAL_REJECTED subjects are provider names with no cross-registry uniqueness, so two rows
   *  can share a code and subject and differ only by action. A key ignoring action dropped one. */
  it('renders every condition even when two share a code and subject', async () => {
    const scm: AttentionItem = {
      code: 'CREDENTIAL_REJECTED',
      severity: 'WARNING',
      subject: 'prod',
      message: "The source-control provider's credential was rejected.",
      action: '/settings/providers',
      dismiss: null,
    };
    const llm: AttentionItem = { ...scm, message: "The LLM provider's credential was rejected.", action: '/settings/llm' };
    await renderWithFeeds([scm, llm], []);
    await waitFor(() => expect(screen.getByTestId('attention-count')).toHaveTextContent('2'));
  });

  /**
   * Only a row describing a past event no fix can clear carries a dismiss. The absence of the control
   * on a repairable condition is the behaviour under test, not an omission.
   */
  it('offers dismiss only on rows that carry one', async () => {
    await renderWithFeeds([blocking, failed], []);
    await waitFor(() => screen.getByTestId('attention-count'));
    screen.getByTestId('attention-toggle').click();
    await waitFor(() => expect(screen.getByText(failed.message)).toBeInTheDocument());

    const buttons = screen.getAllByRole('button', { name: /^Dismiss:/ });
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveAccessibleName('Dismiss: TEST-WS/TEST-REPO#1');
  });

  /**
   * Posts where the server told it to, and does NOT remove the row locally — the server pushes the
   * new list, so what is on screen stays what the server believes.
   */
  it('posts the dismiss path and leaves the row for the server to clear', async () => {
    await renderWithFeeds([failed], []);
    await waitFor(() => screen.getByTestId('attention-count'));
    screen.getByTestId('attention-toggle').click();
    await waitFor(() => expect(screen.getByText(failed.message)).toBeInTheDocument());

    screen.getByRole('button', { name: /^Dismiss:/ }).click();

    await waitFor(() =>
      expect(globalThis.fetch).toHaveBeenCalledWith(failed.dismiss, { method: 'POST' }),
    );
    // Still shown: the row goes when the server says so, not because the UI edited its own list.
    expect(screen.getByText(failed.message)).toBeInTheDocument();

    FakeSocket.push(ORCHESTRATOR, []);
    await waitFor(() => expect(screen.queryByTestId('attention-count')).toBeNull());
  });
});
