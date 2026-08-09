import { renderToStaticMarkup } from 'react-dom/server';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { ReviewStatus, ReviewSummary } from './api';
import ReviewsList from './components/ReviewsList';
import { matchesChip, needsAttention } from './components/ReviewsFilters';
import type { ChipFilter } from './components/ReviewsFilters';
import { miniPipeline, outcomeBadge, STATUS_LABEL } from './render';

/**
 * The `refused` review status (ADR-025): a spend or diff-size cap declined to run the review.
 *
 * <p>These exist because `ReviewStatus` is a **compile-time union over runtime JSON** — `tsc`
 * checks the literal set while the actual value arrives from the orchestrator, so adding a status
 * on the backend cannot break the UI build. It degrades into whatever the default branch does, and
 * here that branch meant "success": a review the deployment refused to spend on rendered as five
 * green segments under "done", with a blank badge, reachable from no chip. Every suite stayed green.
 *
 * <p>So the row is built and rendered rather than the helpers being probed in isolation — the
 * badge alone would still let the green "done" through, which is the actively harmful half.
 */

/** Self-labelling fixture (TEST-* throughout), matching ReviewsList.test.tsx. */
const summary = (over: Partial<ReviewSummary> = {}): ReviewSummary => ({
  id: 'review::TEST-WS/TEST-REPO#1',
  workspace: 'TEST-WS',
  slug: 'TEST-REPO',
  repo: 'TEST-REPO',
  pr: 1,
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
  archivedAt: null,
  ...over,
});

function renderList(reviews: ReviewSummary[]) {
  render(
    <MemoryRouter>
      <ReviewsList
        reviews={reviews}
        loading={false}
        error={null}
        showArchived={false}
        onShowArchivedChange={vi.fn()}
      />
    </MemoryRouter>,
  );
}

const rowFor = (pr: number) => document.querySelector(`[data-id="review::TEST-WS/TEST-REPO#${pr}"]`) as HTMLElement;

describe('a refused review in the reviews list', () => {
  it('says Refused, and never reads as a finished successful review', () => {
    // stage 3 on purpose: the cap refuses mid-pipeline, which is exactly when the old fall-through
    // drew a full bar of completed segments.
    renderList([summary({ status: 'refused', stage: 3 })]);

    const row = rowFor(1);
    expect(within(row).getByText('Refused')).toBeInTheDocument();
    expect(within(row).queryByText('done')).not.toBeInTheDocument();
    expect(row.querySelectorAll('.seg.done')).toHaveLength(0);
  });

  it('is counted and reachable under Needs attention', () => {
    renderList([summary({ status: 'refused', stage: 3 })]);

    // Both surfaces that say "needs attention" must agree with the filter, or the chip reads 0
    // while still opening onto rows. They are two different elements with the same words, so each
    // is addressed by its own container rather than by the text.
    expect(screen.getByRole('button', { name: /needs attention/i })).toHaveTextContent('1');
    expect(document.querySelector('.stat.s-crit')).toHaveTextContent('1');
  });
});

describe('the refused status vocabulary', () => {
  it('has a label, so its badge is not blank', () => {
    // STATUS_LABEL is a Record<ReviewStatus, string>: a missing key is `undefined`, which `pill`
    // renders as an empty span rather than failing anywhere a reader would notice.
    expect(STATUS_LABEL.refused).toBe('Refused');

    const badge = renderToStaticMarkup(<>{outcomeBadge('refused', 0, 0)}</>);
    expect(badge).toContain('Refused');
    expect(badge).toContain('pill refused');
  });

  it('renders no progress bar at all', () => {
    const markup = renderToStaticMarkup(<>{miniPipeline('refused', 3)}</>);

    expect(markup).toContain('refused');
    expect(markup).not.toContain('done');
    expect(markup).not.toContain('seg');
  });

  it('wants an operator, unlike the two Closed statuses', () => {
    expect(needsAttention('refused')).toBe(true);
    expect(needsAttention('failed')).toBe(true);
    expect(needsAttention('cancelled')).toBe(false);
    expect(needsAttention('superseded')).toBe(false);
    expect(needsAttention('completed')).toBe(false);
  });

  it('pins which chip every status reaches, so the next one added cannot land nowhere', () => {
    const chips: ChipFilter[] = ['reviewing', 'completed', 'failed', 'closed'];
    const chipsFor = (status: ReviewStatus) => chips.filter((chip) => matchesChip(status, chip));

    expect(chipsFor('reviewing')).toEqual(['reviewing']);
    expect(chipsFor('completed')).toEqual(['completed']);
    expect(chipsFor('failed')).toEqual(['failed']);
    expect(chipsFor('refused')).toEqual(['failed']);
    expect(chipsFor('cancelled')).toEqual(['closed']);
    expect(chipsFor('superseded')).toEqual(['closed']);

    // `observed` reaches NO chip and predates this work — an observe-mode review is visible only
    // under All. Asserted rather than omitted: a test claiming "every status lands somewhere" while
    // quietly skipping the one that does not would be exactly the vacuous guard this repository has
    // had to fix before.
    expect(chipsFor('observed')).toEqual([]);
  });
});
