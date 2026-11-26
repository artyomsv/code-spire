import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ReviewStatus } from './api';
import { matchesChip, needsAttention } from './components/ReviewsFilters';
import { findCell, miniPipeline } from './components/ReviewRowCells';
import { outcomeBadge, STATUS_LABEL } from './render';

/**
 * Review waits for CI (V121): "Waiting for checks" and "Skipped: checks failed". `ReviewStatus` is a compile-time
 * union over runtime JSON, so each new status is pinned here: its label, a pipeline cell with no green segments, a
 * findings placeholder, and exactly one chip besides All.
 */
const NEW_STATUSES: ReviewStatus[] = ['waiting_for_checks', 'skipped'];

describe('review statuses of "Start review: after checks finish"', () => {
  it('labels both statuses', () => {
    expect(STATUS_LABEL.waiting_for_checks).toBe('Waiting for checks');
    expect(STATUS_LABEL.skipped).toBe('Skipped: checks failed');
    expect(renderToStaticMarkup(outcomeBadge('skipped', 0, 0))).toContain('Skipped: checks failed');
  });

  it.each(NEW_STATUSES)('draws %s in its own colour and name, never as finished segments', status => {
    const cell = renderToStaticMarkup(miniPipeline(status, 0));
    expect(cell).not.toContain('seg done');
    expect(cell).toContain(`seg st-${status}`);
    expect(cell).toContain(status === 'skipped' ? 'Pipeline: skipped — checks failed' : 'Pipeline: waiting for checks');
  });

  it.each(NEW_STATUSES)('shows no findings count for %s: no model saw the diff', status => {
    expect(renderToStaticMarkup(findCell(status, 0))).toContain('—');
  });

  it('puts a waiting review under Reviewing and a skipped one under Closed, neither under Needs attention', () => {
    expect(matchesChip('waiting_for_checks', 'reviewing')).toBe(true);
    expect(matchesChip('skipped', 'closed')).toBe(true);
    for (const status of NEW_STATUSES) {
      expect(needsAttention(status)).toBe(false);
      expect(matchesChip(status, 'completed')).toBe(false);
    }
    expect(matchesChip('waiting_for_checks', 'closed')).toBe(false);
    expect(matchesChip('skipped', 'reviewing')).toBe(false);
  });
});
