import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ReviewStatus } from './api';
import { miniPipeline } from './components/ReviewRowCells';

/**
 * The list's pipeline column draws segments only. The trailing word ("done", "skipped: checks failed") is gone from
 * the screen, so the strip's accessible name must carry it, and each segment names its step and state on hover. No
 * segment is a tab stop: the row is, and its accessible name includes the strip's.
 */
describe('miniPipeline — segments only', () => {
  it.each<[ReviewStatus, number, string]>([
    ['completed', 5, 'Pipeline: done'],
    ['reviewing', 3, 'Pipeline: in progress — Review'],
    ['failed', 2, 'Pipeline: stalled at Context'],
    ['skipped', 0, 'Pipeline: skipped — checks failed'],
    ['refused', 3, 'Pipeline: refused'],
    ['waiting_for_checks', 0, 'Pipeline: waiting for checks'],
  ])('draws %s as five segments named "%s" with no visible word', (status, stage, name) => {
    render(<>{miniPipeline(status, stage)}</>);

    const strip = screen.getByRole('group', { name });
    expect(within(strip).getAllByRole('img')).toHaveLength(5);
    expect(strip.textContent).toBe('');
  });

  it('names each segment by its step and state on hover', () => {
    render(<>{miniPipeline('reviewing', 3)}</>);

    expect(screen.getByRole('img', { name: 'Diff: done' })).toHaveClass('done');
    expect(screen.getByRole('img', { name: 'Comments: not started' })).not.toHaveClass('done');
    const current = screen.getByRole('img', { name: 'Review: in progress' });
    expect(current).toHaveClass('active');

    expect(current).not.toHaveAttribute('tabindex');
    fireEvent.mouseEnter(current);
    expect(screen.getByRole('tooltip')).toHaveTextContent('Review: in progress');
  });

  it('marks the step a failed review stalled at', () => {
    render(<>{miniPipeline('failed', 2)}</>);

    expect(screen.getByRole('img', { name: 'Context: failed' })).toHaveClass('failed');
  });

  /** A skipped review ran nothing: its segments take the status colour, never the green of a finished run. */
  it('draws a skipped review in its own colour, never as done', () => {
    const { container } = render(<>{miniPipeline('skipped', 0)}</>);

    expect(container.querySelectorAll('.seg.st-skipped')).toHaveLength(5);
    expect(container.querySelector('.seg.done')).toBeNull();
    expect(screen.getByRole('img', { name: 'Review: skipped — checks failed' })).toBeInTheDocument();
  });
});
