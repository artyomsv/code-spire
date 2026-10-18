import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import ReviewDetail from './ReviewDetail';
import * as api from '../api';
import type { ReviewDetail as ReviewDetailData } from '../api';

const review: ReviewDetailData = {
  id: 'review::acme/web#42',
  workspace: 'acme',
  slug: 'web',
  repo: 'web',
  pr: 42,
  title: 'Add feature',
  author: 'octocat',
  authorId: '1',
  branch: 'feature',
  base: 'main',
  sha: 'abc1234',
  htmlUrl: 'https://github.com/acme/web/pull/42',
  providerType: 'github',
  prState: 'OPEN',
  status: 'completed',
  stage: 5,
  findings: 0,
  blockerCount: 0,
  updatedAt: '2026-08-01T00:00:00Z',
  unpricedCalls: 0,
  openFindings: 0,
  openBlockers: 0,
  attempt: 1,
  stages: ['done', 'done', 'done', 'done', 'done', 'done'],
  timings: ['0.1s', '0.2s', '0.1s', '1s', '0.5s', '0.1s'],
  findingsList: [],
  chargeLines: [],
  note: null,
  errorDetail: null,
  archivedAt: null,
  degraded: false,
  events: [],
};

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/r/acme/web/42']}>
      <Routes>
        <Route path="/r/:workspace/:slug/:pr" element={<ReviewDetail reviews={[]} />} />
      </Routes>
    </MemoryRouter>,
  );

/** ADR-047: the dashboard says how the forge holds the review, with a link to it. */
describe('ReviewDetail posted verdict', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({ items: [], contributingSources: [], missingSources: [] });
  });

  it('says how the review was posted and links to it', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue({
      ...review, postedVerdict: 'changes_requested', reviewUrl: 'https://github.com/acme/web/pull/42#pullrequestreview-501',
    });
    renderPage();
    const line = await screen.findByText(/Posted as/);
    expect(line).toHaveTextContent('Posted as Changes requested');
    expect(screen.getByRole('link', { name: 'open the review' })).toHaveAttribute('href', 'https://github.com/acme/web/pull/42#pullrequestreview-501');
  });

  /** Review of PR #196 (M-7): the link comes from the forge API, and only a web address is rendered as one. */
  it('does not link a review URL that is not a web address', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue({ ...review, postedVerdict: 'commented', reviewUrl: 'javascript:alert(1)' });
    renderPage();
    const line = await screen.findByText(/Posted as/);
    expect(line).toHaveTextContent('Posted as Comment');
    expect(screen.queryByRole('link', { name: 'open the review' })).toBeNull();
  });

  it('shows nothing for a review posted as a comment thread', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue({ ...review, postedVerdict: null, reviewUrl: null });
    renderPage();
    await screen.findByRole('heading', { name: 'Context', level: 3 });
    expect(screen.queryByText(/Posted as/)).toBeNull();
  });
});
