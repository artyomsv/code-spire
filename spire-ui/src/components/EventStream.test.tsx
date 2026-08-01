import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import EventStream from './EventStream';

const ev = (type: string, at: string) => ({
  type,
  at,
  det: '',
  lane: 'result',
  ts: '2026-08-01T10:00:00Z',
  loc: null,
  threadKind: null,
  threadRef: null,
});

// Two runs, oldest first, as the API returns them.
const review = {
  events: [
    ev('ReviewRequested', '+0.0s'),
    ev('DiffFetched', '+1.2s'),
    ev('ReviewRequested', '+0.0s'),
    ev('DiffFetched', '+1.1s'),
    ev('CommentsPosted', '+9s'),
  ],
} as never;

describe('EventStream', () => {
  it('puts the newest run first and expands only it', () => {
    render(<EventStream r={review} />);

    const groups = screen.getAllByRole('group');
    expect(groups).toHaveLength(2);
    expect(groups[0]).toHaveTextContent(/latest/i);
    expect(groups[0]).toHaveTextContent('CommentsPosted');
    expect(groups[1]).not.toHaveTextContent('DiffFetched');
  });

  /** A run must read in the order it executed, or cause and effect invert while diagnosing. */
  it('keeps events chronological inside a run', () => {
    render(<EventStream r={review} />);

    const text = screen.getAllByRole('group')[0].textContent ?? '';
    expect(text.indexOf('ReviewRequested')).toBeLessThan(text.indexOf('DiffFetched'));
    expect(text.indexOf('DiffFetched')).toBeLessThan(text.indexOf('CommentsPosted'));
  });

  it('expands an older run on demand', () => {
    render(<EventStream r={review} />);

    fireEvent.click(screen.getAllByRole('button')[1]);
    expect(screen.getAllByRole('group')[1]).toHaveTextContent('DiffFetched');
  });

  it('shows a single run without collapsed siblings', () => {
    render(<EventStream r={{ events: [ev('ReviewRequested', '+0.0s'), ev('DiffFetched', '+1s')] } as never} />);

    expect(screen.getAllByRole('group')).toHaveLength(1);
    expect(screen.getByRole('group')).toHaveTextContent('DiffFetched');
  });
});
