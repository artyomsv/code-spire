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
    // Ordering is pinned by the run's own label rather than a "latest" chip: the label is what an
    // operator reads, and it survives the chip being restyled or removed.
    expect(groups[0]).toHaveTextContent('Re-run 1');
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

    const buttons = screen.getAllByRole('button');
    expect(buttons[1]).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(buttons[1]);
    expect(buttons[1]).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getAllByRole('group')[1]).toHaveTextContent('DiffFetched');
  });

  it('shows a single run without collapsed siblings', () => {
    render(<EventStream r={{ events: [ev('ReviewRequested', '+0.0s'), ev('DiffFetched', '+1s')] } as never} />);

    expect(screen.getAllByRole('group')).toHaveLength(1);
    expect(screen.getByRole('group')).toHaveTextContent('DiffFetched');
  });

  /**
   * ReviewDetail re-renders EventStream in place on every live websocket update — it never
   * remounts. If expand state were keyed by display index, a new run prepending to the front
   * would shift every older run's index by one and the operator's expanded run would silently
   * collapse while a different one appeared open in its place.
   */
  it('keeps the operator-expanded run expanded when a new run appears', () => {
    const twoRuns = [
      ev('ReviewRequested', '+0.0s'),
      ev('DiffFetched', '+1.2s'),
      ev('ReviewRequested', '+0.0s'),
      ev('DiffFetched', '+1.1s'),
    ];
    const { rerender } = render(<EventStream r={{ events: twoRuns } as never} />);

    // Expand the older run ("Initial run", displayed last).
    fireEvent.click(screen.getAllByRole('button')[1]);
    expect(screen.getAllByRole('group')[1]).toHaveTextContent('+1.2s');

    // A live update re-renders in place with a new run prepended chronologically.
    const threeRuns = [...twoRuns, ev('ReviewRequested', '+0.0s'), ev('CommentsPosted', '+9s')];
    rerender(<EventStream r={{ events: threeRuns } as never} />);

    const groups = screen.getAllByRole('group');
    expect(groups).toHaveLength(3);
    // "Initial run" now sits at index 2, but stays the one the operator expanded.
    expect(groups[2]).toHaveTextContent('+1.2s');
    // The run that shifted into the old index (Re-run 1) must NOT have inherited that state.
    expect(groups[1]).not.toHaveTextContent('+1.1s');
  });

  /**
   * Real reviews frequently begin with PullRequestEventReceived (appended inline by the
   * orchestrator) before ReviewRequested arrives over the bus. That leading event must fold into
   * the one genuine run rather than opening a phantom run of its own — the earlier version of this
   * test only checked the event wasn't dropped, which passed even while it was mislabelled as a
   * second, falsely-numbered run.
   */
  it('folds a leading PullRequestEventReceived into the initial run, not a run of its own', () => {
    const events = [
      ev('PullRequestEventReceived', '+0.0s'),
      ev('ReviewRequested', '+0.1s'),
      ev('DiffFetched', '+1s'),
    ];
    render(<EventStream r={{ events } as never} />);

    const groups = screen.getAllByRole('group');
    expect(groups).toHaveLength(1);
    expect(groups[0]).toHaveTextContent('Initial run');
    expect(groups[0]).not.toHaveTextContent('Re-run 1');
    expect(groups[0]).toHaveTextContent('PullRequestEventReceived');
  });

  it('numbers a genuine second run as Re-run 1 even when the first run had a leading event', () => {
    const events = [
      ev('PullRequestEventReceived', '+0.0s'),
      ev('ReviewRequested', '+0.1s'),
      ev('DiffFetched', '+1s'),
      ev('ReviewRequested', '+5m'),
    ];
    render(<EventStream r={{ events } as never} />);

    const groups = screen.getAllByRole('group');
    expect(groups).toHaveLength(2);
    expect(groups[0]).toHaveTextContent('Re-run 1');
  });
});
