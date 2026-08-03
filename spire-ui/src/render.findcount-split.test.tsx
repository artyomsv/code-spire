import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { findCell } from './render';

/**
 * A bare open-findings total is ambiguous across rounds: going 1 → 2 reads as "my fix made things
 * worse" when it is just as often one new finding beside one that was already open and never fixed.
 * The breakdown is what makes the movement legible from the list, without opening the review.
 */
describe('findCell — new vs carried-over', () => {
  it('shows the total and how much of it was already open', () => {
    render(<>{findCell('completed', 3, 1)}</>);

    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText(/1 carried/i)).toBeInTheDocument();
  });

  /** The tooltip is where the two halves are named, so neither number is left to inference. */
  it('names both halves in the title', () => {
    const { container } = render(<>{findCell('completed', 3, 1)}</>);

    const title = container.querySelector('.findcount')?.getAttribute('title');
    expect(title).toMatch(/2 raised by this run/i);
    expect(title).toMatch(/1 still open from an earlier run/i);
  });

  /**
   * A first review has nothing carried over, and most reviews are first reviews — they must not pay
   * for a distinction that does not apply, or the column reads as noise on every row.
   */
  it('stays a plain number when nothing is carried over', () => {
    const { container } = render(<>{findCell('completed', 2, 0)}</>);

    expect(screen.getByText('2')).toBeInTheDocument();
    expect(container.querySelector('small')).toBeNull();
    expect(container.querySelector('.findcount')).not.toHaveAttribute('title');
  });

  it('still shows a clean review as zero', () => {
    const { container } = render(<>{findCell('completed', 0, 0)}</>);

    expect(screen.getByText('0')).toBeInTheDocument();
    expect(container.querySelector('.findcount')).toHaveClass('zero');
  });

  /**
   * A running review's tally is noise — "0 so far" reads as a result — and that must hold whether or
   * not the previous run left something open.
   */
  it('shows a placeholder while the review is still running', () => {
    const { container } = render(<>{findCell('reviewing', 2, 1)}</>);

    expect(container.querySelector('.findcount')).toBeNull();
    expect(screen.getByText('—')).toBeInTheDocument();
  });
});
