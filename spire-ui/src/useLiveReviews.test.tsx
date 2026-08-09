import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLiveReviews } from './useLiveReviews';
import type { ReviewSummary } from './api';

/**
 * The reviews list is fed by two sources at once — a REST snapshot on mount and a WebSocket that
 * pushes for the rest of the session — and the rules for reconciling them live nowhere but this
 * hook. They are the kind of logic that regresses silently: every guard here fails by showing
 * plausible-looking rows rather than by throwing, so nothing in the UI would look broken.
 */

/** A stand-in for the browser's WebSocket; jsdom has none. Mirrors the harness in AttentionBell. */
class FakeSocket {
  static open: FakeSocket[] = [];

  onmessage: ((ev: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(public url: string) {
    FakeSocket.open.push(this);
  }

  close() {
    this.closed = true;
  }

  static get latest(): FakeSocket {
    const socket = FakeSocket.open[FakeSocket.open.length - 1];
    if (!socket) throw new Error('no socket was opened');
    return socket;
  }

  /** Deliver one frame, as the server does on connect and on every change. */
  static push(payload: unknown) {
    act(() => {
      FakeSocket.latest.onmessage?.({ data: JSON.stringify(payload) });
    });
  }

  /** Deliver a raw frame, for the payloads a well-behaved server would never send. */
  static pushRaw(data: string) {
    act(() => {
      FakeSocket.latest.onmessage?.({ data });
    });
  }
}

/**
 * Obviously-synthetic rows: the workspace, repo and commit are self-labelling so a fixture can never
 * be mistaken for a real review if one ever leaks into a screenshot or a log.
 */
function summary(id: string, updatedAt: string, over: Partial<ReviewSummary> = {}): ReviewSummary {
  return {
    id,
    workspace: 'TEST-WS',
    slug: 'TEST-REPO',
    repo: 'TEST-REPO',
    pr: 1,
    title: 'TEST review',
    author: 'TEST-USER',
    authorId: 'TEST-1',
    branch: 'TEST-BRANCH',
    base: 'TEST-BASE',
    sha: 'TESTSHA00000',
    htmlUrl: 'https://example.invalid/pr/1',
    providerType: 'github',
    prState: 'OPEN',
    status: 'completed',
    stage: 6,
    findings: 0,
    blockerCount: 0,
    carriedOverFindings: 0,
    costMillicents: 0,
    model: '',
    llmType: '',
    updatedAt,
    unpricedCalls: 0,
    ...over,
  };
}

const OLDER = summary('review::TEST-WS/TEST-REPO#1', '2026-01-01T00:00:00Z');
const NEWER = summary('review::TEST-WS/TEST-REPO#2', '2026-01-02T00:00:00Z');

/** Resolves the REST snapshot only when the test says so, so "late" is a state a test can reach. */
function deferredFetch() {
  let settle: (rows: ReviewSummary[]) => void = () => {};
  let fail: (e: Error) => void = () => {};
  const pending = new Promise<ReviewSummary[]>((resolve, reject) => {
    settle = resolve;
    fail = reject;
  });
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve({ ok: true, json: () => pending } as unknown as Response)),
  );
  return {
    resolve: async (rows: ReviewSummary[]) => {
      await act(async () => {
        settle(rows);
        await pending.catch(() => undefined);
      });
    },
    reject: async (message: string) => {
      await act(async () => {
        fail(new Error(message));
        await pending.catch(() => undefined);
      });
    },
  };
}

function respondWith(rows: ReviewSummary[]) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve(rows) } as unknown as Response)),
  );
}

describe('useLiveReviews', () => {
  beforeEach(() => {
    FakeSocket.open = [];
    vi.stubGlobal('WebSocket', FakeSocket);
    respondWith([]);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('sorts the REST snapshot newest first and stops loading', async () => {
    respondWith([OLDER, NEWER]);

    const { result } = renderHook(() => useLiveReviews());

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.reviews.map((r) => r.id)).toEqual([NEWER.id, OLDER.id]);
    expect(result.current.error).toBeNull();
  });

  it('surfaces a REST failure as an error rather than an empty list', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('network is down'))));

    const { result } = renderHook(() => useLiveReviews());

    await waitFor(() => expect(result.current.error).toBe('network is down'));
    expect(result.current.loading).toBe(false);
  });

  /**
   * The guard the whole hook is built around. The socket routinely wins the race against a slower
   * REST call, and applying the snapshot afterwards would silently roll the list back to the state
   * it had when the request was issued — a review that just completed would show as still running,
   * and nothing would look wrong.
   */
  it('ignores a REST snapshot that arrives after the socket has delivered', async () => {
    const rest = deferredFetch();
    const { result } = renderHook(() => useLiveReviews());
    await waitFor(() => expect(FakeSocket.open).toHaveLength(1));

    FakeSocket.push([NEWER]);
    expect(result.current.reviews.map((r) => r.id)).toEqual([NEWER.id]);

    await rest.resolve([OLDER]); // the stale snapshot lands second

    expect(result.current.reviews.map((r) => r.id)).toEqual([NEWER.id]);
    expect(result.current.loading).toBe(false);
  });

  /** Same guard on the failure path: a live list on screen must not be captioned with an error. */
  it('ignores a REST failure that arrives after the socket has delivered', async () => {
    const rest = deferredFetch();
    const { result } = renderHook(() => useLiveReviews());
    await waitFor(() => expect(FakeSocket.open).toHaveLength(1));

    FakeSocket.push([NEWER]);
    await rest.reject('network is down');

    expect(result.current.error).toBeNull();
    expect(result.current.reviews.map((r) => r.id)).toEqual([NEWER.id]);
  });

  it('replaces the list from an array frame and upserts from an object frame', async () => {
    const { result } = renderHook(() => useLiveReviews());
    await waitFor(() => expect(result.current.loading).toBe(false));

    FakeSocket.push([OLDER]);
    expect(result.current.reviews.map((r) => r.id)).toEqual([OLDER.id]);

    FakeSocket.push(NEWER); // unknown id — appended, and sorts ahead
    expect(result.current.reviews.map((r) => r.id)).toEqual([NEWER.id, OLDER.id]);

    // known id — replaced in place, not duplicated, and re-sorted on its new timestamp
    FakeSocket.push(summary(OLDER.id, '2026-01-03T00:00:00Z', { status: 'reviewing' }));
    expect(result.current.reviews.map((r) => r.id)).toEqual([OLDER.id, NEWER.id]);
    expect(result.current.reviews).toHaveLength(2);
    expect(result.current.reviews[0].status).toBe('reviewing');
  });

  it('drops the row a removal frame names, and leaves the others', async () => {
    const { result } = renderHook(() => useLiveReviews());
    await waitFor(() => expect(result.current.loading).toBe(false));
    FakeSocket.push([OLDER, NEWER]);

    FakeSocket.push({ removed: OLDER.id });

    expect(result.current.reviews.map((r) => r.id)).toEqual([NEWER.id]);
  });

  /**
   * A summary with no string id would key its React row on undefined — duplicate keys, rows that
   * merge into each other, and an upsert that can never match. Dropping it is the hook's contract.
   */
  it('drops a payload that carries no string id', async () => {
    const { result } = renderHook(() => useLiveReviews());
    await waitFor(() => expect(result.current.loading).toBe(false));

    FakeSocket.push([NEWER, { workspace: 'TEST-WS', pr: 9 }]);

    expect(result.current.reviews.map((r) => r.id)).toEqual([NEWER.id]);
  });

  it('ignores a frame that is not JSON instead of tearing down the socket', async () => {
    const { result } = renderHook(() => useLiveReviews());
    await waitFor(() => expect(result.current.loading).toBe(false));
    FakeSocket.push([NEWER]);

    FakeSocket.pushRaw('<html>gateway timeout</html>');

    expect(result.current.reviews.map((r) => r.id)).toEqual([NEWER.id]);
  });

  /**
   * A close now asks WHY before reconnecting — an expired session must send the operator to a login
   * rather than reopening forever — so the reconnect is one promise later than it used to be.
   */
  it('reconnects after the socket closes for a reason that is not authentication', async () => {
    // The shared harness already answers /api/reviews; this makes /api/me unreachable, so the hook
    // must carry on rather than read "unknown session" as a login prompt.
    vi.useFakeTimers();
    renderHook(() => useLiveReviews());
    expect(FakeSocket.open).toHaveLength(1);

    // The close handler now awaits a session check before scheduling anything, so the microtasks
    // must be flushed before the timer it arms even exists.
    await act(async () => {
      FakeSocket.latest.onclose?.();
    });
    act(() => {
      vi.advanceTimersByTime(1500);
    });

    expect(FakeSocket.open).toHaveLength(2);
  });

  /**
   * The failure this replaced: a socket rejected because the session expired closes with no usable
   * code, and the default session lifetime is five minutes — so a blind retry loop hammered the
   * identity provider every 1.5 seconds, indefinitely, on an entirely ordinary expiry.
   */
  it('sends the operator to a login instead of reconnecting when the session has expired', async () => {
    // Both the snapshot and /api/me come through this one stub; the session reads as expired.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ authEnabled: true, authenticated: false, user: '', roles: [] }),
    }));
    const assign = vi.fn();
    vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost' });

    renderHook(() => useLiveReviews());
    await act(async () => {
      FakeSocket.latest.onclose?.();
    });

    // Chained: an expired dashboard session means no session on any prefix, so all three are asked for
    // in one redirect sequence rather than each being discovered missing a page load at a time.
    expect(assign).toHaveBeenCalledWith('/api/auth/login?chain=1');
    expect(FakeSocket.open).toHaveLength(1);
    vi.unstubAllGlobals();
  });

  /**
   * Unmount must close the socket AND cancel a pending reconnect. Leaving the timer armed reopens a
   * socket for a page that is gone, and on a dashboard that is navigated all day those accumulate.
   */
  it('closes the socket on unmount and schedules no further reconnect', async () => {
    vi.useFakeTimers();
    const { unmount } = renderHook(() => useLiveReviews());
    const socket = FakeSocket.latest;

    act(() => {
      socket.onclose?.(); // a close already in flight when the page navigates away
    });
    unmount();
    act(() => {
      vi.advanceTimersByTime(5000);
    });

    expect(socket.closed).toBe(true);
    expect(FakeSocket.open).toHaveLength(1);
  });
});
