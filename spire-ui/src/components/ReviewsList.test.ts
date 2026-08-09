import { describe, expect, it } from 'vitest';
import type { ReviewSummary } from '../api';
import { costCell } from './ReviewsList';

/**
 * Self-labelling fixture (TEST-* throughout): a review row can never be mistaken for a real one if
 * it leaks into a screenshot or a log.
 */
const summary = (over: Partial<ReviewSummary> = {}): ReviewSummary => ({
  id: 'review::TEST-WS/TEST-REPO#1',
  workspace: 'TEST-WS',
  slug: 'TEST-REPO',
  repo: 'TEST-REPO',
  pr: 1,
  title: 'TEST review',
  author: 'TEST-USER',
  authorId: 'TEST-1',
  branch: 'TEST-BRANCH',
  base: 'TEST-BASE',
  sha: 'TESTSHA00000',
  htmlUrl: 'https://example.invalid/pr/1',
  providerType: 'github',
  prState: 'OPEN',
  status: 'completed',
  stage: 6,
  findings: 0,
  blockerCount: 0,
  carriedOverFindings: 0,
  costMillicents: 0,
  model: '',
  llmType: '',
  updatedAt: '2026-08-07T00:00:00Z',
  unpricedCalls: 0,
  archivedAt: null,
  ...over,
});

/**
 * `costMillicents` sums to 0 in three situations that must not read the same on the busiest screen
 * in the app: no charge yet, an asserted UNMETERED zero, and every charge that landed being UNKNOWN.
 */
describe('costCell', () => {
  it('reads as no charge at all when no model has ever been charged for this review', () => {
    const cell = costCell(summary({ model: '', costMillicents: 0, unpricedCalls: 0 }));
    expect(cell.text).toBe('—');
  });

  it('shows the complete total once a charge has landed and nothing is unpriced', () => {
    const cell = costCell(summary({ model: 'TEST-MODEL', costMillicents: 25_000, unpricedCalls: 0 }));
    expect(cell.text).toBe('$0.250');
    expect(cell.title).toBe('Review cost');
  });

  it('marks a non-zero total partial, rather than presenting it as complete', () => {
    const cell = costCell(summary({ model: 'TEST-MODEL', costMillicents: 25_000, unpricedCalls: 1 }));
    expect(cell.text).toBe('$0.250*');
    expect(cell.title).toMatch(/1 call\(s\) could not be priced/i);
  });

  /**
   * The condition this branch exists to make loud: nothing could be priced at all. A naive
   * `formatCost(0)` here would print "$0.000*", which at a glance reads as "free" — indistinguishable
   * from an UNMETERED model's genuine zero. It must read as unpriced, not as a priced amount.
   */
  it('reads as unpriced rather than as a free total when every charge is unknown', () => {
    const cell = costCell(summary({ model: 'TEST-MODEL', costMillicents: 0, unpricedCalls: 1 }));
    expect(cell.text).toBe('— (unpriced)');
    expect(cell.text).not.toContain('$0.000');
  });
});
