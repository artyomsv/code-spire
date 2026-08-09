import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import App from './App';

/**
 * Show archived, wired end to end through the real shell.
 *
 * The point of rendering `App` rather than the list is that the list is *presentational* — it takes
 * rows as props and never fetches. The checkbox therefore has to reach a fetch it does not own, and
 * a test against the list alone would happily pass with the two ends unconnected.
 */

/** jsdom has no WebSocket, and the shell opens three. Nothing here pushes a frame. */
class SilentSocket {
  onmessage: ((ev: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  constructor(public url: string) {}
  close() {}
}

/** Self-labelling rows: TEST-* throughout, so neither can be mistaken for a real review. */
function summary(pr: number, archivedAt: string | null) {
  return {
    id: `review::TEST-WS/TEST-REPO#${pr}`,
    workspace: 'TEST-WS',
    slug: 'TEST-REPO',
    repo: 'TEST-REPO',
    pr,
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
    updatedAt: '2026-08-09T00:00:00Z',
    unpricedCalls: 0,
    archivedAt,
  };
}

const LIVE = summary(1, null);
const ARCHIVED = summary(2, '2026-08-09T01:00:00Z');

const ADMIN_SESSION = {
  authEnabled: true,
  authenticated: true,
  user: 'test-operator',
  roles: ['spire-admin', 'spire-viewer'],
};

/** Only the archived-inclusive listing carries the archived row — as the endpoint behaves. */
function payloadFor(url: string): unknown {
  if (/\/api\/me$/.test(url)) return ADMIN_SESSION;
  if (/\/api\/reviews\?includeArchived=true$/.test(url)) return [LIVE, ARCHIVED];
  if (/\/api\/reviews$/.test(url)) return [LIVE];
  return [];
}

let urls: string[] = [];

const jsonResponse = (payload: unknown) => ({
  ok: true,
  status: 200,
  json: async () => payload,
  text: async () => JSON.stringify(payload),
});

describe('App — Show archived', () => {
  beforeEach(() => {
    urls = [];
    vi.stubGlobal('WebSocket', SilentSocket);
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        urls.push(url);
        return Promise.resolve(jsonResponse(payloadFor(url)));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const renderList = () =>
    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    );

  it('asks for live rows only until the box is checked', async () => {
    renderList();

    expect(await screen.findByText('#1')).toBeInTheDocument();
    expect(screen.queryByText('#2')).not.toBeInTheDocument();
    expect(urls.some((u) => u.includes('includeArchived'))).toBe(false);
  });

  it('requests archived rows and shows them once the box is checked', async () => {
    renderList();
    await screen.findByText('#1');

    fireEvent.click(screen.getByLabelText(/show archived/i));

    await waitFor(() =>
      expect(urls.some((u) => u.includes('includeArchived=true'))).toBe(true),
    );
    expect(await screen.findByText('#2')).toBeInTheDocument();
    // Still inline in the same table, alongside the live row — not a separate view.
    expect(screen.getByText('#1')).toBeInTheDocument();
  });

  it('drops the archived rows again when the box is cleared', async () => {
    renderList();
    await screen.findByText('#1');

    fireEvent.click(screen.getByLabelText(/show archived/i));
    await screen.findByText('#2');
    fireEvent.click(screen.getByLabelText(/show archived/i));

    await waitFor(() => expect(screen.queryByText('#2')).not.toBeInTheDocument());
    expect(screen.getByText('#1')).toBeInTheDocument();
  });
});
