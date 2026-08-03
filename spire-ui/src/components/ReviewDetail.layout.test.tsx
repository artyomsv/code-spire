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
  carriedOverFindings: 0,
  costMillicents: 100,
  model: 'gpt-5',
  llmType: 'openai',
  updatedAt: '2026-08-01T00:00:00Z',
  openFindings: 0,
  openBlockers: 0,
  attempt: 1,
  stages: ['done', 'done', 'done', 'done', 'done', 'done'],
  timings: ['0.1s', '0.2s', '0.1s', '1s', '0.5s', '0.1s'],
  findingsList: [],
  usage: null,
  llmCalls: [{ kind: 'review', model: 'gpt-5', tokensIn: 100, tokensOut: 50, costMillicents: 100 }],
  note: null,
  errorDetail: null,
  events: [
    { ts: '2026-08-01T00:00:00Z', at: '+0.0s', lane: 'domain', type: 'ReviewRequested', det: '' },
    { ts: '2026-08-01T00:00:01Z', at: '+1.0s', lane: 'result', type: 'CommentsPosted', det: '' },
  ],
};

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/r/acme/web/42']}>
      <Routes>
        <Route path="/r/:workspace/:slug/:pr" element={<ReviewDetail reviews={[]} />} />
      </Routes>
    </MemoryRouter>,
  );

/**
 * Model usage grows by a row per LLM call, so a re-run review pushes the fixed Metadata card off
 * the bottom of the screen. Metadata comes first.
 */
describe('ReviewDetail layout', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(review);
    vi.spyOn(api, 'fetchReviewContext').mockResolvedValue({
      items: [],
      contributingSources: [],
      missingSources: [],
    });
  });

  it('renders Metadata above Model usage', async () => {
    renderPage();

    const headings = (await screen.findAllByRole('heading', { level: 3 })).map((h) => h.textContent);
    expect(headings.indexOf('Metadata')).toBeLessThan(headings.indexOf('Model usage'));
  });

  it('renders the Context card', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Context', level: 3 })).toBeInTheDocument();
  });
});
