import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ReviewStatus } from '../api';
import { statusCell } from '../render';
import { listStatusCell } from './ReviewStatusIcon';

/**
 * The reviews list draws a review's status as one coloured icon; the review header keeps the word badge. The icon's
 * accessible name and tooltip are the header's full word, so nothing the badge said is lost, and the list cell holds
 * no badge text at all (PR #457: "Skipped: checks failed" ran into the PR icon).
 */
interface Case {
  status: ReviewStatus;
  findings?: number;
  blockerCount?: number;
  degraded?: boolean;
  label: string;
  tone: string;
}

const CASES: Case[] = [
  { status: 'completed', label: 'Passed', tone: 'good' },
  { status: 'completed', findings: 2, blockerCount: 1, label: 'Changes', tone: 'crit' },
  { status: 'completed', findings: 2, label: 'Suggestions', tone: 'blue' },
  { status: 'completed', degraded: true, label: 'No result', tone: 'warn' },
  { status: 'reviewing', label: 'Reviewing', tone: 'spin' },
  { status: 'failed', label: 'Attention', tone: 'crit' },
  { status: 'waiting_for_checks', label: 'Waiting for checks', tone: 'muted' },
  { status: 'skipped', label: 'Skipped: checks failed', tone: 'warn' },
  { status: 'refused', label: 'Refused', tone: 'crit' },
  { status: 'cancelled', label: 'Cancelled', tone: 'muted' },
  { status: 'superseded', label: 'Superseded', tone: 'muted' },
  { status: 'observed', label: 'Observed', tone: 'muted' },
];

const row = (c: Case) => ({
  status: c.status, findings: c.findings ?? 0, blockerCount: c.blockerCount ?? 0, degraded: c.degraded,
});

describe('the reviews list status icon', () => {
  it.each(CASES)('draws $status as "$label" in its own colour, with no word badge', (c) => {
    const { container } = render(<>{listStatusCell(row(c))}</>);

    const icon = screen.getByRole('img', { name: c.label });
    expect(icon).toHaveClass('status-icon', `st-icon-${c.tone}`);
    expect(icon).not.toHaveAttribute('tabindex');
    expect(container.querySelector('.pill')).toBeNull();
    expect(container).toHaveTextContent(/^$/);

    fireEvent.mouseEnter(icon);
    expect(screen.getByRole('tooltip')).toHaveTextContent(c.label);
  });

  it('keeps the responding icon beside the status icon', () => {
    render(<>{listStatusCell({ status: 'completed', findings: 0, blockerCount: 0, answering: true })}</>);

    expect(screen.getByRole('img', { name: 'Passed' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Responding to a reply…' })).toBeInTheDocument();
  });
});

describe('the review header status badge', () => {
  it.each(CASES)('still says "$label" in full as a word', (c) => {
    const { container } = render(<>{statusCell(row(c), 0)}</>);

    const badge = container.querySelector('.pill');
    expect(badge).toHaveTextContent(new RegExp(`^${c.label}$`));
    expect(badge).not.toHaveAttribute('aria-label');
  });
});
