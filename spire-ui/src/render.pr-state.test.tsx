import { fireEvent, render, screen } from '@testing-library/react';
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

    fireEvent.focus(icon);
    expect(screen.getByRole('tooltip')).toHaveTextContent(name);
  });
});
