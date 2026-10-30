import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { fireEvent, waitFor } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router';
import { queryWrapper } from '../test/queryWrapper';
import ReviewDetail from './ReviewDetail';
import * as api from '../api';
import type { ReviewDetail as ReviewDetailData } from '../api';
import { renderWithQuery } from '../test/queryWrapper';

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
  renderWithQuery(
    <MemoryRouter initialEntries={['/r/acme/web/42']}>
      <Routes>
        <Route path="/r/:workspace/:slug/:pr" element={<ReviewDetail reviews={[]} />} />
      </Routes>
    </MemoryRouter>,
  );

const page = (reviews: api.ReviewSummary[] = []) => (
  <MemoryRouter initialEntries={['/r/acme/web/42']}>
    <Link to="/r/acme/web/43">TEST next review</Link>
    <Routes>
      <Route path="/r/:workspace/:slug/:pr" element={<ReviewDetail reviews={reviews} />} />
    </Routes>
  </MemoryRouter>
);

describe('ReviewDetail reads', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({ items: [], contributingSources: [], missingSources: [] });
    vi.spyOn(api, 'fetchPrDescription').mockResolvedValue(null);
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(review);
  });

  it('shows the review at once when it opens again', async () => {
    const wrapper = queryWrapper();
    const first = renderWithQuery(page(), { wrapper });
    expect(await screen.findByText('Add feature')).toBeInTheDocument();
    first.unmount();
    vi.mocked(api.fetchReviewDetail).mockReturnValue(new Promise(() => {}));
    renderWithQuery(page(), { wrapper });
    expect(screen.getByText('Add feature')).toBeInTheDocument();
  });

  it('re-reads the detail once when its summary changes, never for the first summary seen', async () => {
    const view = renderWithQuery(page([review]));
    expect(await screen.findByText('Add feature')).toBeInTheDocument();
    expect(api.fetchReviewDetail).toHaveBeenCalledTimes(1);
    view.rerender(page([{ ...review }]));
    expect(api.fetchReviewDetail).toHaveBeenCalledTimes(1);
    view.rerender(page([{ ...review, updatedAt: '2026-08-02T00:00:00Z' }]));
    await waitFor(() => expect(api.fetchReviewDetail).toHaveBeenCalledTimes(2));
  });

  // Opening another review must not re-read it because the previous review's summary differed.
  it('reads another review once when the page moves to it', async () => {
    const other = { ...review, id: 'review::acme/web#43', pr: 43, title: 'TEST other', updatedAt: '2026-08-05T00:00:00Z' };
    vi.mocked(api.fetchReviewDetail).mockImplementation(async (_w, _s, pr) => (String(pr) === '43' ? other : review));
    renderWithQuery(page([review, other]));
    expect(await screen.findByText('Add feature')).toBeInTheDocument();
    fireEvent.click(screen.getByText('TEST next review'));
    expect(await screen.findByText('TEST other')).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(vi.mocked(api.fetchReviewDetail).mock.calls.filter(([, , pr]) => String(pr) === '43')).toHaveLength(1);
  });

  it('keeps the detail beside a failed re-read', async () => {
    const view = renderWithQuery(page([review]));
    expect(await screen.findByText('Add feature')).toBeInTheDocument();
    vi.mocked(api.fetchReviewDetail).mockRejectedValue(new Error('TEST-BOOM'));
    view.rerender(page([{ ...review, updatedAt: '2026-08-02T00:00:00Z' }]));
    expect(await screen.findByRole('alert')).toHaveTextContent('TEST-BOOM');
    expect(screen.getByText('Add feature')).toBeInTheDocument();
  });
});

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
