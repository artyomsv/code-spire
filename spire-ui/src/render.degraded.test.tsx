import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { findCell } from './render';
import { matchesChip, needsAttention } from './components/ReviewsFilters';

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
  it('replaces the findings count with a marker rather than a zero', () => {
    render(<>{findCell('completed', 0, 0, true)}</>);
    expect(screen.getByText('no output')).toBeInTheDocument();
    expect(screen.queryByText('0')).not.toBeInTheDocument();
  });

  it('warns rather than alarms', () => {
    // Nothing is broken and no outage is in progress — the run needs re-running. `--crit` would
    // read as an incident, `--muted` as nothing to do.
    const { container } = render(<>{findCell('completed', 0, 0, true)}</>);
    expect(container.querySelector('.pill--warn')).not.toBeNull();
    expect(container.querySelector('.pill--crit')).toBeNull();
  });

  it('leaves an ordinary clean review showing its zero', () => {
    // The discriminator. Without it every completed review would carry the marker.
    render(<>{findCell('completed', 0, 0, false)}</>);
    expect(screen.getByText('0')).toBeInTheDocument();
    expect(screen.queryByText('no output')).not.toBeInTheDocument();
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
});
