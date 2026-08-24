import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import * as api from '../api';
import PromptSamplePicker from './PromptSamplePicker';

/**
 * Self-labelling fixture (TEST-* throughout): a review row can never be mistaken for a real one if
 * it leaks into a screenshot or a log. Field-for-field the real `ReviewSummary` shape (`api.ts`),
 * not a convenient subset — the picker reads `id`/`workspace`/`slug`/`pr` off it.
 */
const reviewRow = (over: Partial<api.ReviewSummary> = {}): api.ReviewSummary => ({
  id: 'review::TEST-WS/TEST-REPO#7',
  workspace: 'TEST-WS',
  slug: 'TEST-REPO',
  repo: 'TEST-REPO',
  pr: 7,
  title: 'TEST review',
  author: 'TEST-USER',
  authorId: 'TEST-1',
  branch: 'TEST-BRANCH',
  base: 'TEST-BASE',
  sha: 'TESTSHA00000',
  htmlUrl: 'https://example.invalid/pr/7',
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
  archivedAt: null,
  ...over,
});

const annotatedPreview = (): api.PromptPreview => ({
  system: 's', user: '«diff inserted here»', errors: [], sampleReviewId: null, unavailableReason: null,
});

const samplePreview = (): api.PromptPreview => ({
  system: 's', user: 'real diff text', errors: [],
  sampleReviewId: 'review::TEST-WS/TEST-REPO#7', unavailableReason: null,
});

describe('PromptSamplePicker', () => {
  it('previews against no review by default', async () => {
    vi.spyOn(api, 'fetchReviews').mockResolvedValue([]);
    const preview = vi.spyOn(api, 'previewPrompt').mockResolvedValue(annotatedPreview());
    render(<PromptSamplePicker kind="review" system="s" body="b" />);

    fireEvent.click(screen.getByRole('button', { name: /preview/i }));

    await waitFor(() => expect(preview).toHaveBeenCalledWith('review', 's', 'b', undefined, api.GLOBAL_SCOPE));
  });

  it('previews against the selected review', async () => {
    // The dropdown lists real reviews (label `workspace/slug#pr`) but must pass the SELECTED
    // review's own `id` — the real reviewId format (`review::workspace/slug#pr`) — to the API, not
    // the display label.
    const preview = vi.spyOn(api, 'previewPrompt').mockResolvedValue(samplePreview());
    vi.spyOn(api, 'fetchReviews').mockResolvedValue([reviewRow()]);
    render(<PromptSamplePicker kind="review" system="s" body="b" />);

    // This project's dropdown is a custom accessible combobox (Select.tsx), not a native <select> —
    // opened and driven the same way every other test that touches it does (e.g.
    // SettingsWebhookRepos.form.test.tsx): click the trigger, then click the option.
    fireEvent.click(await screen.findByRole('combobox', { name: /sample/i }));
    const option = await screen.findByRole('option', { name: 'TEST-WS/TEST-REPO#7' });
    fireEvent.click(option);

    fireEvent.click(screen.getByRole('button', { name: /preview/i }));

    await waitFor(() => expect(preview).toHaveBeenCalledWith(
      'review', 's', 'b', 'review::TEST-WS/TEST-REPO#7', api.GLOBAL_SCOPE,
    ));
  });

  /**
   * L6: the picker omitted the scope argument entirely and had no scope prop, so the editor at
   * `?scope=acme/widgets` sent `scope=*` to preview -- defeating the "a mistaken caller fails loud"
   * rationale `PromptResource`'s own javadoc gives for validating scope on every endpoint, preview
   * included.
   */
  it('previews at the scope the editor was given, not always global', async () => {
    vi.spyOn(api, 'fetchReviews').mockResolvedValue([]);
    const preview = vi.spyOn(api, 'previewPrompt').mockResolvedValue(annotatedPreview());
    render(<PromptSamplePicker kind="review" system="s" body="b" scope="acme/widgets" />);

    fireEvent.click(screen.getByRole('button', { name: /preview/i }));

    await waitFor(() => expect(preview).toHaveBeenCalledWith('review', 's', 'b', undefined, 'acme/widgets'));
  });

  it('shows why a sample was unavailable instead of an empty panel', async () => {
    vi.spyOn(api, 'fetchReviews').mockResolvedValue([]);
    vi.spyOn(api, 'previewPrompt').mockResolvedValue({
      system: 's', user: '«diff inserted here»', errors: [],
      sampleReviewId: null, unavailableReason: 'diff fetch failed (404)',
    });
    render(<PromptSamplePicker kind="review" system="s" body="b" />);

    fireEvent.click(screen.getByRole('button', { name: /preview/i }));

    expect(await screen.findByText(/diff fetch failed \(404\)/)).toBeInTheDocument();
  });

  it('previews against no review even when the review list fails to load', async () => {
    // The annotated preview is the default and must not depend on the picker's own list fetch
    // succeeding — a deployment with no reviews, or a flaky reviews endpoint, must not block it.
    vi.spyOn(api, 'fetchReviews').mockRejectedValue(new Error('network down'));
    const preview = vi.spyOn(api, 'previewPrompt').mockResolvedValue(annotatedPreview());
    render(<PromptSamplePicker kind="review" system="s" body="b" />);

    fireEvent.click(screen.getByRole('button', { name: /preview/i }));

    await waitFor(() => expect(preview).toHaveBeenCalledWith('review', 's', 'b', undefined, api.GLOBAL_SCOPE));
  });
});
