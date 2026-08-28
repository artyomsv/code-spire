import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { findCell, findingsCard, outcomeBadge } from './render';
import { matchesChip, needsAttention, type ChipFilter } from './components/ReviewsFilters';
import type { ReviewDetail, ReviewStatus } from './api';

/**
 * A run the model produced nothing usable for reports zero findings — and zero findings is exactly
 * what a clean review reports. On this screen the two were byte-identical, which is where the
 * symptom was actually observed: a paid review that reviewed nothing rendered as done, green, with
 * a `0`. An attention row and a detail-page note do not fix the surface being looked at.
 *
 * <p>Same class as the `refused` incident: a new backend state the list's own types could not see,
 * defaulting into the success rendering.
 */
describe('a degraded review is distinguishable on the list', () => {
  it('shows no finding count at all, the way a failed run does', () => {
    // A count for a review that produced nothing is not a count. The outcome badge carries the
    // state instead, exactly as it does for failed, cancelled and refused.
    render(<>{findCell('completed', 0, 0, true)}</>);
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });

  it('replaces the outcome badge, which would otherwise claim the review passed', () => {
    // The most prominent cell on the row. Zero findings is what a clean pass writes too, so this
    // badge asserted "Passed" about a review that never happened.
    render(<>{outcomeBadge('completed', 0, 0, true)}</>);
    expect(screen.getByText('No result')).toBeInTheDocument();
    expect(screen.queryByText('Passed')).not.toBeInTheDocument();
  });

  it('warns rather than alarms', () => {
    // Nothing is broken and no outage is in progress — the run needs re-running. `--crit` would
    // read as an incident, `--muted` as nothing to do.
    const { container } = render(<>{outcomeBadge('completed', 0, 0, true)}</>);
    expect(container.querySelector('.pill--warn')).not.toBeNull();
    expect(container.querySelector('.pill--crit')).toBeNull();
  });

  it('leaves an ordinary clean review passing, with its zero', () => {
    // The discriminator. Without it every completed review would be relabelled.
    render(<>{findCell('completed', 0, 0, false)}{outcomeBadge('completed', 0, 0, false)}</>);
    expect(screen.getByText('0')).toBeInTheDocument();
    expect(screen.getByText('Passed')).toBeInTheDocument();
  });

  it('still shows a real finding count when the run was not degraded', () => {
    render(<>{findCell('completed', 3, 0, false)}</>);
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('counts toward Needs attention', () => {
    expect(needsAttention('completed', true)).toBe(true);
    expect(needsAttention('completed', false)).toBe(false);
  });

  it('answers to the Needs attention chip and not to Completed', () => {
    // Exactly one chip, or the counts stop adding up and a row exists that two chips both claim.
    expect(matchesChip('completed', 'failed', true)).toBe(true);
    expect(matchesChip('completed', 'completed', true)).toBe(false);
    expect(matchesChip('completed', 'completed', false)).toBe(true);
    expect(matchesChip('completed', 'failed', false)).toBe(false);
  });

  /**
   * The degraded axis must never make a row reach TWO chips.
   *
   * <p>`render.refused.test.tsx` already pins the status-to-chip map (including that `observed`
   * deliberately reaches none). What it cannot see is this flag, which is why it is checked here:
   * the flag outlives the run that set it, so an ungated test claimed a review being re-reviewed
   * right now for both Reviewing and Needs attention. The chip counts are derived from this same
   * predicate, so a row claimed twice made them sum to more rows than the list held.
   */
  it.each([
    'reviewing', 'completed', 'failed', 'cancelled', 'superseded', 'refused', 'observed',
  ] as ReviewStatus[])('never lets degraded put %s under two chips', (status) => {
    const chips: ChipFilter[] = ['reviewing', 'completed', 'failed', 'closed'];
    expect(chips.filter((f) => matchesChip(status, f, true)).length).toBeLessThanOrEqual(1);
    expect(chips.filter((f) => matchesChip(status, f, false)).length).toBeLessThanOrEqual(1);
  });

  it('moves a completed review from Completed to Needs attention, and only that status', () => {
    // The flag only means anything on a run that finished, mirroring the REVIEW_DEGRADED query,
    // which filters on status for the same reason.
    expect(matchesChip('completed', 'completed', true)).toBe(false);
    expect(matchesChip('completed', 'failed', true)).toBe(true);
    expect(matchesChip('reviewing', 'reviewing', true)).toBe(true);
    expect(matchesChip('reviewing', 'failed', true)).toBe(false);
    expect(matchesChip('cancelled', 'closed', true)).toBe(true);
    expect(matchesChip('cancelled', 'failed', true)).toBe(false);
  });
});

/**
 * The detail page, which the list-row work did not reach — the same gap the `refused` incident had.
 *
 * <p>`STATUS_EXPLANATIONS` is keyed by status, so it structurally cannot see a condition that is not
 * one. A degraded run is `completed`, so it fell past that branch to the empty-findings rendering and
 * told the operator the diff was reviewed and clean. The note — the only place the actionable text
 * appears anywhere in the UI — lives inside that branch, so it was written, stored, sent over the
 * wire and shown nowhere.
 */
describe('a degraded review on the detail page', () => {
  const detail = (over: Partial<ReviewDetail> = {}): ReviewDetail =>
    ({
      status: 'completed',
      degraded: true,
      note: 'The model returned no usable review — TEST note.',
      findingsList: [],
      reconciliation: [],
      openFindings: 0,
      openBlockers: 0,
      errorDetail: null,
      ...over,
    }) as ReviewDetail;

  it('explains itself instead of claiming the diff was clean', () => {
    render(<>{findingsCard(detail())}</>);
    expect(screen.getByText('Why there is no result')).toBeInTheDocument();
    // Asserted explicitly: a test that only looked for the new heading would pass with the "clean"
    // claim still rendered beside it.
    expect(screen.queryByText(/clean/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/No issues found/i)).not.toBeInTheDocument();
  });

  it('renders the note, which appears nowhere else in the UI', () => {
    render(<>{findingsCard(detail())}</>);
    expect(screen.getByText(/TEST note/)).toBeInTheDocument();
  });

  it('says so rather than showing an empty card when no note was recorded', () => {
    render(<>{findingsCard(detail({ note: null }))}</>);
    expect(screen.getByText(/No explanation was recorded/)).toBeInTheDocument();
  });

  it('leaves an ordinary clean review saying it is clean', () => {
    // The discriminator: without it every completed review would claim it produced no result.
    render(<>{findingsCard(detail({ degraded: false }))}</>);
    expect(screen.queryByText('Why there is no result')).not.toBeInTheDocument();
  });
});
