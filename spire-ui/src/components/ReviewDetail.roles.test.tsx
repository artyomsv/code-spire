import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import ReviewDetail from './ReviewDetail';
import * as api from '../api';
import * as auth from '../auth';
import type { Me } from '../auth';

const detail = {
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
  updatedAt: '2026-08-03T00:00:00Z',
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
} as unknown as api.ReviewDetail;

const me = (roles: string[]): Me => ({
  authEnabled: true,
  authenticated: true,
  user: 'test-operator',
  roles,
});

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/r/TEST-WS/TEST-REPO/1']}>
      <Routes>
        <Route path="/r/:workspace/:slug/:pr" element={<ReviewDetail reviews={[]} />} />
      </Routes>
    </MemoryRouter>,
  );
}

/**
 * Re-run and delete are admin: one spends money on a fresh model call, the other is irreversible.
 * The API refuses them either way — this is about not offering a viewer a control that can only
 * fail, which reads as the app being broken rather than as a permission they lack.
 */
describe('ReviewDetail — role-aware actions', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchReviewDetail').mockResolvedValue(detail);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ items: [] }) }));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('offers re-run and delete to an admin', async () => {
    vi.spyOn(auth, 'fetchMe').mockResolvedValue(me(['spire-viewer', 'spire-admin']));
    renderDetail();

    expect(await screen.findByRole('button', { name: /re-run review/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /delete review/i })).toBeInTheDocument();
  });

  it('offers neither to a viewer', async () => {
    vi.spyOn(auth, 'fetchMe').mockResolvedValue(me(['spire-viewer']));
    renderDetail();

    await screen.findByText('TEST fixture pull request');
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /delete review/i })).not.toBeInTheDocument(),
    );
    expect(screen.queryByRole('button', { name: /re-run review/i })).not.toBeInTheDocument();
  });

  /** Dev runs with authentication off; the dashboard must not be crippled by having no roles. */
  it('offers everything when authentication is switched off', async () => {
    vi.spyOn(auth, 'fetchMe').mockResolvedValue({
      authEnabled: false,
      authenticated: false,
      user: '',
      roles: [],
    });
    renderDetail();

    expect(await screen.findByRole('button', { name: /delete review/i })).toBeInTheDocument();
  });
});
