import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { PrState } from './api';
import { PrStateIcon } from './components/ReviewIcons';

/**
 * The pull request's own state as an icon only (open green, merged purple, closed red), distinct from the review's
 * status badge. The word is its accessible name and its tooltip, never visible text.
 */
describe('PrStateIcon', () => {
  it.each<[PrState, string, string]>([
    ['OPEN', 'Pull request open', 'pr-open'],
    ['MERGED', 'Pull request merged', 'pr-merged'],
    ['CLOSED', 'Pull request closed', 'pr-closed'],
  ])('names %s by its state', (state, name, cls) => {
    render(<PrStateIcon prState={state} />);

    const icon = screen.getByRole('img', { name });
    expect(icon).toHaveClass(cls);
    expect(icon).not.toHaveTextContent(/open|merged|closed/i);

    expect(icon).not.toHaveAttribute('tabindex'); // a list row is the keyboard stop, not its marks

    fireEvent.mouseEnter(icon);
    expect(screen.getByRole('tooltip')).toHaveTextContent(name);
  });

  /** The detail header has no row to stand in for its marks, so there they are tab stops and open on focus. */
  it('is a tab stop in the detail header, and its tooltip opens on focus', () => {
    render(<PrStateIcon prState="MERGED" tabIndex={0} />);

    const icon = screen.getByRole('img', { name: 'Pull request merged' });
    act(() => icon.focus()); // real focus: the mark is a tab stop here

    expect(document.activeElement).toBe(icon);
    expect(screen.getByRole('tooltip')).toHaveTextContent('Pull request merged');
  });
});
