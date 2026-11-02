import type { AttentionItem } from '../api';

const SEVERITY_RANK: Record<AttentionItem['severity'], number> = { BLOCKING: 0, WARNING: 1 };

/** Every per-review code carries it: REVIEW_STUCK, REVIEW_FAILED, REVIEW_DEGRADED, REVIEW_HARNESS_REFUSED. */
const REVIEW_PREFIX = 'REVIEW_';

/** Whether a row is about one review. */
export function isReviewRow(item: AttentionItem): boolean {
  return item.code.startsWith(REVIEW_PREFIX);
}

/** Code-unit order, as Java's String.compareTo, so the merged list agrees with each service's own order. */
function compareText(a: string, b: string): number {
  if (a === b) return 0;
  return a < b ? -1 : 1;
}

/** Epoch milliseconds, or null when the row carries no time or one that does not parse. */
function sinceOf(item: AttentionItem): number | null {
  if (!item.since) return null;
  const at = Date.parse(item.since);
  return Number.isNaN(at) ? null : at;
}

/** Newest first; a row with no known time after every timed one. */
function compareSince(a: AttentionItem, b: AttentionItem): number {
  const left = sinceOf(a);
  const right = sinceOf(b);
  if (left === right) return 0;
  if (left === null) return 1;
  if (right === null) return -1;
  return right - left;
}

function compareAttention(a: AttentionItem, b: AttentionItem): number {
  return (
    SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity] ||
    Number(isReviewRow(b)) - Number(isReviewRow(a)) ||
    compareSince(a, b) ||
    compareText(a.code, b.code) ||
    compareText(a.subject ?? '', b.subject ?? '')
  );
}

/**
 * The bell's one order, the same as AttentionOrder.java's: blockers first, then rows about a single review, then
 * newest first, then code and subject. Applied after the two feeds merge, since each service can only order its own.
 * Returns a new list.
 */
export function orderAttention(items: readonly AttentionItem[]): AttentionItem[] {
  return [...items].sort(compareAttention);
}
