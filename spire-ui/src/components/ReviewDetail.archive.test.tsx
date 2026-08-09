import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import ReviewDetail from './ReviewDetail';
import * as api from '../api';
import * as auth from '../auth';
import type { Me } from '../auth';

/**
 * Archiving replaced deleting, and the two are not the same offer. Delete was irreversible and took
 * the review's recorded spend with it; archive destroys nothing and can be undone from this page —
 * so the control, its confirmation copy and its counterpart all have to say so.
 */

const detail = (archivedAt: string | null) =>
  ({
    id: 'review::TEST-WS/TEST-REPO#1',
    workspace: 'TEST-WS',
    slug: 'TEST-REPO',
    repo: 'TEST-REPO',
    pr: 1,
    title: 'TEST fixture pull request',
    author: 'TEST-AUTHOR',
    authorId: 'TEST-0',
    branch: 'TEST-branch',
    base: 'main',
    sha: 'TESTSHA00000',
    htmlUrl: 'https://example.invalid/TEST-WS/TEST-REPO/pull/1',
    providerType: 'github',
    prState: 'OPEN',
    status: 'completed',
    stage: 5,
    findings: 0,
    blockerCount: 0,
    carriedOverFindings: 0,
    costMillicents: 0,
    model: '',
    llmType: '',
    updatedAt: '2026-08-09T00:00:00Z',
    unpricedCalls: 0,
    openFindings: 0,
    openBlockers: 0,
    attempt: 1,
    stages: ['done', 'done', 'done', 'done', 'done', 'done'],
    timings: ['', '', '', '', '', ''],
    findingsList: [],
    chargeLines: [],
    note: null,
    errorDetail: null,
    events: [],
    archivedAt,
  }) as unknown as api.ReviewDetail;

const ADMIN: Me = {
  authEnabled: true,
  authenticated: true,
  user: 'test-operator',
  roles: ['spire-viewer', 'spire-admin'],
};

let posted: Array<{ url: string; init?: RequestInit }> = [];

/** Every POST answers 204, as the archive and unarchive endpoints do on success. */
function stubFetch(conflictBody?: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        posted.push({ url, init });
        if (conflictBody) {
          return Promise.resolve({
            ok: false,
            status: 409,
            headers: { get: () => 'text/plain' },
            text: async () => conflictBody,
          });
        }
        return Promise.resolve({ ok: true, status: 204, text: async () => '' });
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: async () => ({ items: [], contributingSources: [], missingSources: [], description: null }),
        text: async () => '{}',
      });
    }),
  );
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/r/TEST-WS/TEST-REPO/1']}>
      <Routes>
        <Route path="/r/:workspace/:slug/:pr" element={<ReviewDetail reviews={[]} />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('ReviewDetail — archive and unarchive', () => {
  beforeEach(() => {
    posted = [];
    vi.spyOn(auth, 'fetchMe').mockResolvedValue(ADMIN);
    stubFetch();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('archives instead of deleting', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(detail(null));
    renderDetail();

    fireEvent.click(await screen.findByRole('button', { name: /^archive review$/i }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /^archive review$/i }));

    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0].url).toContain('/api/reviews/TEST-WS/TEST-REPO/1/archive');
    expect(posted[0].init?.method).toBe('POST');
  });

  /** The old copy promised permanent destruction. Archiving destroys nothing, so it must not. */
  it('does not promise to destroy anything', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(detail(null));
    renderDetail();

    fireEvent.click(await screen.findByRole('button', { name: /^archive review$/i }));
    const dialog = await screen.findByRole('dialog');

    expect(within(dialog).queryByText(/permanently|cannot be undone/i)).not.toBeInTheDocument();
    expect(within(dialog).getByText(/no further reviews/i)).toBeInTheDocument();
  });

  it('offers unarchive on an archived review and never both at once', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(detail('2026-08-09T01:00:00Z'));
    renderDetail();

    expect(await screen.findByRole('button', { name: /unarchive review/i })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^archive review$/i })).not.toBeInTheDocument();
  });

  /**
   * A re-run of an archived review is refused by the API (it is retired), so offering the button
   * would only produce a failure the operator cannot act on.
   */
  it('withdraws the re-run button from an archived review', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(detail('2026-08-09T01:00:00Z'));
    renderDetail();

    await screen.findByRole('button', { name: /unarchive review/i });
    expect(screen.queryByRole('button', { name: /re-run review/i })).not.toBeInTheDocument();
  });

  it('posts to unarchive from an archived review', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(detail('2026-08-09T01:00:00Z'));
    renderDetail();

    fireEvent.click(await screen.findByRole('button', { name: /unarchive review/i }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /unarchive review/i }));

    await waitFor(() => expect(posted).toHaveLength(1));
    expect(posted[0].url).toContain('/api/reviews/TEST-WS/TEST-REPO/1/unarchive');
  });

  /**
   * The 409 body is the whole reason the endpoint builds a response entity: it says WHICH of the
   * three refusals happened and what to do about it. A generic "failed to archive" throws that away.
   */
  it('surfaces the refusal the server actually sent', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(detail(null));
    stubFetch('This review is still running. Wait for it to finish, or cancel it, then archive.');
    renderDetail();

    fireEvent.click(await screen.findByRole('button', { name: /^archive review$/i }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /^archive review$/i }));

    expect(await within(dialog).findByText(/still running/i)).toBeInTheDocument();
  });

  /** A viewer sees neither: both are admin, and the API refuses them either way. */
  it('offers neither control to a viewer', async () => {
    vi.spyOn(auth, 'fetchMe').mockResolvedValue({ ...ADMIN, roles: ['spire-viewer'] });
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(detail('2026-08-09T01:00:00Z'));
    renderDetail();

    await screen.findByText('TEST fixture pull request');
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /unarchive review/i })).not.toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: /^archive review$/i })).not.toBeInTheDocument();
  });

  /**
   * A viewer gets no buttons at all, so with the badge missing an archived review would read to them
   * exactly like a live one that had gone quiet.
   */
  it('says on the page itself that the review is archived', async () => {
    vi.spyOn(auth, 'fetchMe').mockResolvedValue({ ...ADMIN, roles: ['spire-viewer'] });
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(detail('2026-08-09T01:00:00Z'));
    renderDetail();

    expect(await screen.findByTitle(/archived/i)).toBeInTheDocument();
  });
});
