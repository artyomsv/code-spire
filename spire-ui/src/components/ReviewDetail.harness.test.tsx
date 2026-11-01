import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
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

    expect(await screen.findByText('Harness run')).toBeInTheDocument();
    const href = `/runs/${encodeURIComponent(HARNESS_RUN)}`;
    await waitFor(() => expect(screen.getAllByRole('link').some(link => link.getAttribute('href') === href)).toBe(true));
    expect(api.getRuns).toHaveBeenCalledWith(expect.objectContaining({ reviewId: review.id, kind: 'REVIEW' }));
  });

  it('the review page shows no run link for an API review', async () => {
    renderPage();

    expect(await screen.findByText('TEST harness review')).toBeInTheDocument();
    await waitFor(() => expect(api.getRuns).toHaveBeenCalledWith(expect.objectContaining({ reviewId: review.id, kind: 'REVIEW' })));
    expect(screen.queryByText('Harness run')).not.toBeInTheDocument();
    expect(screen.getAllByRole('link').some(link => (link.getAttribute('href') ?? '').startsWith('/runs/'))).toBe(false);
  });
});
