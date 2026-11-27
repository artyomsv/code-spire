import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { statusCell } from './render';

/**
 * The status cell shared by the reviews-list row and the detail header: exactly one status badge, and the bot
 * composing a reply (`answering`) is a small icon beside it rather than a second badge, named for a screen reader and
 * explained on hover (and on focus in the detail header, where it is a tab stop).
 */
describe('statusCell — one badge and the responding icon', () => {
  it('draws one status badge and the responding icon while answering', () => {
    const { container } = render(<>{statusCell({ status: 'completed', findings: 0, blockerCount: 0, answering: true })}</>);

    expect(container.querySelectorAll('.pill')).toHaveLength(1);
    const icon = screen.getByRole('img', { name: 'Responding to a reply…' });
    expect(container.querySelector('.responding')).toBeNull();
    expect(screen.queryByText(/responding…/)).toBeNull();

    expect(icon).not.toHaveAttribute('tabindex');
    fireEvent.mouseEnter(icon);
    expect(screen.getByRole('tooltip')).toHaveTextContent('Responding to a reply…');
  });

  it('is a tab stop in the detail header, and its tooltip opens on focus', () => {
    render(<>{statusCell({ status: 'completed', findings: 0, blockerCount: 0, answering: true }, 0)}</>);

    const icon = screen.getByRole('img', { name: 'Responding to a reply…' });
    act(() => icon.focus()); // real focus: the mark is a tab stop here

    expect(document.activeElement).toBe(icon);
    expect(screen.getByRole('tooltip')).toHaveTextContent('Responding to a reply…');
  });

  it('draws no responding icon when answering is false', () => {
    const { container } = render(<>{statusCell({ status: 'completed', findings: 0, blockerCount: 0, answering: false })}</>);

    expect(container.querySelectorAll('.pill')).toHaveLength(1);
    expect(screen.queryByRole('img', { name: /responding/i })).toBeNull();
  });

  it('draws no responding icon when answering is absent', () => {
    render(<>{statusCell({ status: 'completed', findings: 0, blockerCount: 0 })}</>);

    expect(screen.queryByRole('img', { name: /responding/i })).toBeNull();
  });
});
