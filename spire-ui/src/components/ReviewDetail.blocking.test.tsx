import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import ReviewDetail from './ReviewDetail';
import * as api from '../api';
import type { ReviewDetail as ReviewDetailData } from '../api';
import { renderWithQuery } from '../test/queryWrapper';

/** One open MAJOR finding and nothing above it: what blocks depends on the repository's level (V115). */
const majorOnly: ReviewDetailData = {
  id: 'review::acme/web#42',
  workspace: 'acme',
  slug: 'web',
  repo: 'web',
  pr: 42,
  title: 'TEST threshold review',
  author: 'TEST-author',
  authorId: '1',
  branch: 'feature',
  base: 'main',
  sha: 'abc1234',
  htmlUrl: 'https://example.invalid/acme/web/pull/42',
  providerType: 'github',
  prState: 'OPEN',
  status: 'completed',
  stage: 5,
  findings: 1,
  blockerCount: 0,
  updatedAt: '2026-08-01T00:00:00Z',
  unpricedCalls: 0,
  openFindings: 1,
  openBlockers: 0,
  openBlocking: 0,
  blockApprovalAt: 'BLOCKER',
  attempt: 1,
  stages: ['done', 'done', 'done', 'done', 'done', 'done'],
  timings: ['', '', '', '', '', ''],
  findingsList: [{ sev: 'warning', loc: 'src/A.java:3', msg: 'TEST major' }],
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

const outcome = () => document.querySelector('.dhead .pill');

describe('ReviewDetail blocking count', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({ items: [], contributingSources: [], missingSources: [] });
    vi.spyOn(api, 'fetchPrDescription').mockResolvedValue(null);
  });

  it('counts a MAJOR as blocking at Major and above, and says the level', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue({ ...majorOnly, openBlocking: 1, blockApprovalAt: 'MAJOR' });
    renderPage();
    expect(await screen.findByText('1 blocking (Major and above)')).toBeInTheDocument();
    expect(outcome()).toHaveTextContent('Changes');
  });

  it('does not block on a MAJOR at Blocker', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(majorOnly);
    renderPage();
    await screen.findByText('TEST threshold review');
    expect(screen.queryByText(/blocking/)).toBeNull();
    expect(outcome()).toHaveTextContent('Suggestions');
  });

  it('needs no qualifier at Blocker', async () => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue({ ...majorOnly, openBlockers: 2, openBlocking: 2 });
    renderPage();
    expect(await screen.findByText('2 blocking')).toBeInTheDocument();
  });
});
