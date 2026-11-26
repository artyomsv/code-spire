import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { findCell, findingsBreakdown } from './components/ReviewRowCells';

/**
 * A bare open-findings total is ambiguous across rounds: going 1 → 2 reads as "my fix made things worse" when it is
 * just as often one new finding beside one that was already open and never fixed. The list shows the number alone;
 * the split is its tooltip and its screen-reader text, so the column stays narrow and the movement stays legible.
 */
describe('findCell — the number, with the split on hover', () => {
  it('shows the number only, with no carried label', () => {
    const { container } = render(<>{findCell({ status: 'completed', findings: 7, carriedOverFindings: 3 })}</>);

    expect(screen.getByText('7')).toBeInTheDocument();
    expect(screen.queryByText(/carried/i, { ignore: '.sr-only, script, style' })).toBeNull();
    expect(container.querySelector('small')).toBeNull();
  });

  it('names the split on hover', () => {
    render(<>{findCell({ status: 'completed', findings: 7, carriedOverFindings: 3 })}</>);

    fireEvent.mouseEnter(screen.getByText('7'));
    expect(screen.getByRole('tooltip')).toHaveTextContent('7 open: 4 new, 3 carried');
  });

  it('says the split to a screen reader', () => {
    const { container } = render(<>{findCell({ status: 'completed', findings: 7, carriedOverFindings: 3 })}</>);

    expect(container.querySelector('.sr-only')).toHaveTextContent('7 open: 4 new, 3 carried');
  });

  it('says a first review is all new', () => {
    expect(findingsBreakdown({ status: 'completed', findings: 2, carriedOverFindings: 0 })).toBe('2 open: 2 new, 0 carried');
  });

  it('still shows a clean review as zero', () => {
    const { container } = render(<>{findCell({ status: 'completed', findings: 0, carriedOverFindings: 0 })}</>);

    expect(screen.getByText('0')).toBeInTheDocument();
    expect(container.querySelector('.findcount')).toHaveClass('zero');
    expect(findingsBreakdown({ status: 'completed', findings: 0, carriedOverFindings: 0 })).toBe('No open findings');
  });

  /** A running review's tally is noise — "0 so far" reads as a result — whatever the previous run left open. */
  it('shows a placeholder while the review is still running', () => {
    const { container } = render(<>{findCell({ status: 'reviewing', findings: 2, carriedOverFindings: 1 })}</>);

    expect(container.querySelector('.findcount')).toBeNull();
    expect(screen.getByText('—')).toBeInTheDocument();
  });
});
