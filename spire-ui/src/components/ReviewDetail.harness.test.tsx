import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import ReviewDetail from './ReviewDetail';
import * as api from '../api';
import type { ReviewDetail as ReviewDetailData } from '../api';
import { renderWithQuery } from '../test/queryWrapper';
import { runRow } from '../test/liveRuns';

/** Test fixture only: every identifier is TEST-prefixed or an example name. */
const review: ReviewDetailData = {
  id: 'review::acme/web#42',
  workspace: 'acme',
  slug: 'web',
  repo: 'web',
  pr: 42,
  title: 'TEST harness review',
  author: 'TEST-author',
  authorId: '1',
  branch: 'feature',
  base: 'main',
  sha: 'abc1234',
  htmlUrl: 'https://forge.example.test/acme/web/pull/42',
  providerType: 'github',
  prState: 'OPEN',
  status: 'reviewing',
  stage: 3,
  findings: 0,
  blockerCount: 0,
  updatedAt: '2026-08-01T00:00:00Z',
  unpricedCalls: 0,
  openFindings: 0,
  openBlockers: 0,
  openBlocking: 0,
  blockApprovalAt: 'BLOCKER',
  attempt: 1,
  stages: ['done', 'done', 'done', 'active', 'pending', 'pending'],
  timings: ['0.1s', '0.2s', '0.1s', '', '', ''],
  findingsList: [],
  chargeLines: [],
  note: null,
  errorDetail: null,
  archivedAt: null,
  degraded: false,
  events: [],
};

const HARNESS_RUN = 'run::github:acme/web:review-42-TEST-0123abcd:1';
const RECONCILE_RUN = 'run::github:acme/web:review-42-TEST-4567cdef:2';
const FIX_RUN = 'run::github:acme/web:fix-42-TEST-89abcdef:1';

const runHref = (runId: string) => `/runs/${encodeURIComponent(runId)}`;

/** The line that links a run: its label, the run and its status. */
const runLine = async (runId: string): Promise<HTMLElement> => {
  const link = await waitFor(() => {
    const found = screen.getAllByRole('link').find(candidate => candidate.getAttribute('href') === runHref(runId));
    if (!found) throw new Error(`no link to ${runId}`);
    return found;
  });
  return link.closest('p') as HTMLElement;
};

const renderPage = () =>
  renderWithQuery(
    <MemoryRouter initialEntries={['/r/acme/web/42']}>
      <Routes>
        <Route path="/r/:workspace/:slug/:pr" element={<ReviewDetail reviews={[]} />} />
      </Routes>
    </MemoryRouter>,
  );

/** Harness reviews, step 5: the review page links the run that is reviewing it, so its transcript is one click away. */
describe('ReviewDetail harness run', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({ items: [], contributingSources: [], missingSources: [] });
    vi.spyOn(api, 'fetchPrDescription').mockResolvedValue(null);
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(review);
    vi.spyOn(api, 'getRuns').mockResolvedValue([]);
  });

  it('the review page links its harness run', async () => {
    vi.mocked(api.getRuns).mockResolvedValue([runRow({ runId: HARNESS_RUN, kind: 'REVIEW', status: 'running', reviewId: review.id })]);

    renderPage();

    expect((await runLine(HARNESS_RUN)).textContent).toContain('Review');
    expect(api.getRuns).toHaveBeenCalledWith({ reviewId: review.id, limit: 20 });
  });

  it('the review page shows no run link for an API review', async () => {
    renderPage();

    expect(await screen.findByText('TEST harness review')).toBeInTheDocument();
    await waitFor(() => expect(api.getRuns).toHaveBeenCalledWith({ reviewId: review.id, limit: 20 }));
    expect(screen.getAllByRole('link').some(link => (link.getAttribute('href') ?? '').startsWith('/runs/'))).toBe(false);
  });

  /** Harness reviews, step 6: a re-review runs as one RECONCILE run, and the page links it too. */
  it("the review page links a re-review's run", async () => {
    vi.mocked(api.getRuns).mockResolvedValue([runRow({ runId: RECONCILE_RUN, kind: 'RECONCILE', status: 'running', reviewId: review.id })]);

    renderPage();

    const line = await runLine(RECONCILE_RUN);
    expect(line.textContent).toContain('Re-review');
    expect(line.textContent).not.toContain('Harness run');
  });

  /** The server lists a review's runs newest first, so the first review-round run is the current round's. */
  it('the review page links the newest review run', async () => {
    vi.mocked(api.getRuns).mockResolvedValue([
      runRow({ runId: RECONCILE_RUN, kind: 'RECONCILE', status: 'running', reviewId: review.id, startedAt: '2026-08-02T00:00:00Z' }),
      runRow({ runId: HARNESS_RUN, kind: 'REVIEW', status: 'succeeded', reviewId: review.id, startedAt: '2026-08-01T00:00:00Z' }),
    ]);

    renderPage();

    expect((await runLine(RECONCILE_RUN)).textContent).toContain('Re-review');
    expect(screen.getAllByRole('link').some(link => link.getAttribute('href') === runHref(HARNESS_RUN))).toBe(false);
  });

  /** A fix run of the same review is not the review's own run: it is never linked as one. */
  it('a fix run of the same review is never linked', async () => {
    vi.mocked(api.getRuns).mockResolvedValue([runRow({ runId: FIX_RUN, kind: 'FIX', status: 'running', reviewId: review.id })]);

    renderPage();

    expect(await screen.findByText('TEST harness review')).toBeInTheDocument();
    await waitFor(() => expect(api.getRuns).toHaveBeenCalled());
    // The read has answered and its answer has rendered, so an absent link is a decision, not a read still out.
    await act(async () => { await vi.mocked(api.getRuns).mock.results[0].value; });
    expect(screen.getAllByRole('link').some(link => (link.getAttribute('href') ?? '').startsWith('/runs/'))).toBe(false);
  });
});
