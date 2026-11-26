import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { findCell, type FindingsRow } from './components/ReviewRowCells';
import { rowSummaryParts } from './components/RowFocusSummary';
import type { ReviewSummary, SeverityCounts } from './api';

/**
 * The list's findings column: one badge per severity with open findings, most severe first, in the findings card's
 * colours, so "7" no longer hides whether it is one blocker or seven nits. The words are the group's tooltip, its
 * screen-reader text and the row's focus line.
 */
const counts = (critical: number, warning: number, suggestion: number, nit: number): SeverityCounts =>
  ({ critical, warning, suggestion, nit });

const row = (openBySeverity: SeverityCounts | null | undefined, findings = 7, carried = 3): FindingsRow =>
  ({ status: 'completed', findings, carriedOverFindings: carried, openBySeverity });

function badges(container: HTMLElement) {
  return [...container.querySelectorAll('.sevbadge')].map((b) => [b.classList[1], b.textContent]);
}

describe('findCell — one badge per severity', () => {
  it('draws a badge per present severity, most severe first, with its count', () => {
    const { container } = render(<>{findCell(row(counts(1, 2, 4, 0)))}</>);

    expect(badges(container)).toEqual([['sev-critical', '1'], ['sev-warning', '2'], ['sev-suggestion', '4']]);
    expect(container.querySelector('.findcount')).toBeNull();
  });

  it('skips a severity with nothing open', () => {
    const { container } = render(<>{findCell(row(counts(0, 0, 3, 4)))}</>);

    expect(badges(container)).toEqual([['sev-suggestion', '3'], ['sev-nit', '4']]);
  });

  it('caps at three severities and puts the rest in one +N badge', () => {
    const { container } = render(<>{findCell(row(counts(1, 1, 2, 5), 9))}</>);

    expect(badges(container)).toEqual([
      ['sev-critical', '1'], ['sev-warning', '1'], ['sev-suggestion', '2'], ['sev-more', '+5'],
    ]);
  });

  it('names every severity and the new/carried split on hover', () => {
    const { container } = render(<>{findCell(row(counts(1, 2, 4, 0)))}</>);

    fireEvent.mouseEnter(container.querySelector('.sevbadges')!);
    expect(screen.getByRole('tooltip')).toHaveTextContent(
      '7 open: 1 critical, 2 warnings, 4 suggestions; 4 new, 3 carried');
  });

  it('says the words, not the bare badge numbers, to a screen reader', () => {
    const { container } = render(<>{findCell(row(counts(1, 1, 2, 5), 9))}</>);

    expect(container.querySelector('.sevbadges .sr-only')).toHaveTextContent(
      '9 open: 1 critical, 1 warning, 2 suggestions, 5 nits; 6 new, 3 carried');
    for (const badge of container.querySelectorAll('.sevbadge')) expect(badge).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('findCell — where the badges do not apply', () => {
  it('keeps the bare number when the row has no per-severity counts', () => {
    for (const missing of [undefined, null]) {
      const { container, unmount } = render(<>{findCell(row(missing))}</>);
      expect(container.querySelector('.sevbadges')).toBeNull();
      expect(container.querySelector('.findcount')).toHaveTextContent('7');
      expect(container.querySelector('.sr-only')).toHaveTextContent('7 open: 4 new, 3 carried');
      unmount();
    }
  });

  it('keeps the bare number when the counts do not add up to the total', () => {
    const { container } = render(<>{findCell(row(counts(1, 0, 0, 0)))}</>);

    expect(container.querySelector('.sevbadges')).toBeNull();
    expect(container.querySelector('.findcount')).toHaveTextContent('7');
  });

  it('keeps a clean review as a green zero', () => {
    const { container } = render(<>{findCell(row(counts(0, 0, 0, 0), 0, 0))}</>);

    expect(container.querySelector('.findcount')).toHaveClass('zero');
    expect(container.querySelector('.sevbadges')).toBeNull();
  });

  it('keeps the dash for a review no model finished reading', () => {
    const { container } = render(<>{findCell({ ...row(counts(1, 2, 4, 0)), status: 'reviewing' })}</>);

    expect(container.querySelector('.sevbadges')).toBeNull();
    expect(screen.getByText('—')).toBeInTheDocument();
  });
});

describe('the row focus line says the same words', () => {
  it('ends with the severity breakdown', () => {
    const summary = {
      status: 'completed', stage: 6, findings: 7, blockerCount: 1, carriedOverFindings: 3,
      openBySeverity: counts(1, 2, 4, 0), prState: 'OPEN', providerType: 'github', htmlUrl: '', degraded: false,
    } as unknown as ReviewSummary;

    expect(rowSummaryParts(summary).slice(-1)[0]).toBe('7 open: 1 critical, 2 warnings, 4 suggestions; 4 new, 3 carried');
  });
});
