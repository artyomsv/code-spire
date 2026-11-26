import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import type { ReviewSummary } from '../api';
import ReviewsList from './ReviewsList';

/**
 * Self-labelling fixture (TEST-* throughout): a review row can never be mistaken for a real one if
 * it leaks into a screenshot or a log.
 */
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

function renderList(reviews: ReviewSummary[], onShowArchivedChange = vi.fn(), showArchived = false) {
  render(
    <MemoryRouter>
      <ReviewsList
        reviews={reviews}
        loading={false}
        error={null}
        showArchived={showArchived}
        onShowArchivedChange={onShowArchivedChange}
      />
    </MemoryRouter>,
  );
  return onShowArchivedChange;
}

const rowFor = (pr: number) => document.querySelector(`[data-id="review::TEST-WS/TEST-REPO#${pr}"]`) as HTMLElement;

describe('ReviewsList — archived rows', () => {
  /**
   * Archived rows sit inline in the same table as live work, so the row itself has to say which it
   * is. Unmarked, a frozen review reads as a current one that simply stopped updating.
   */
  it('marks an archived row so it cannot be mistaken for live work', () => {
    renderList([summary({ archivedAt: '2026-08-09T01:00:00Z' })]);

    expect(within(rowFor(1)).getByText(/archived/i)).toBeInTheDocument();
  });

  /** The discriminating half: the marker must be the archival, not something every row carries. */
  it('leaves a live row unmarked', () => {
    renderList([summary()]);

    expect(within(rowFor(1)).queryByText(/archived/i)).not.toBeInTheDocument();
  });
});

describe('ReviewsList — the Show archived control', () => {
  /**
   * The checkbox reports upward rather than deciding locally: the list never fetches, so the state
   * belongs where the request is made. This is the half of that wiring the list owns.
   */
  it('reports a check upward instead of filtering in place', () => {
    const onChange = renderList([summary()]);

    fireEvent.click(screen.getByLabelText(/show archived/i));

    expect(onChange).toHaveBeenCalledWith(true);
  });

  it('reflects the state it is given', () => {
    renderList([summary()], vi.fn(), true);

    expect(screen.getByLabelText(/show archived/i)).toBeChecked();
  });
});

/**
 * A row is the list's one keyboard stop; its marks are not tab stops. So the row's own accessible name must carry the
 * words its icons and strips dropped (status, pull request state, responding, the findings split), for a keyboard or
 * screen-reader user who cannot hover. Enter opens the detail, where they are shown in full.
 */
describe('ReviewsList — the row names what its marks show', () => {
  it('names the status, the pull request state, the reply in progress and the findings split', () => {
    renderList([summary({ prState: 'MERGED', answering: true, findings: 3, carriedOverFindings: 1, blockerCount: 1 })]);

    const row = rowFor(1);
    expect(row).toHaveAttribute('role', 'button');
    expect(row).toHaveAccessibleName(/Changes/);
    expect(row).toHaveAccessibleName(/Responding to a reply…/);
    expect(row).toHaveAccessibleName(/Pull request merged/);
    expect(row).toHaveAccessibleName(/3 open: 2 new, 1 carried/);
    expect(row).toHaveAccessibleName(/Pipeline: done/);
    expect(row).toHaveAttribute('tabindex', '0');
    expect(row.querySelectorAll('[role="img"][tabindex]')).toHaveLength(0);
  });
});
