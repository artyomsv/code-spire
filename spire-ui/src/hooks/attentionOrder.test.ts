import { describe, expect, it } from 'vitest';
import type { AttentionItem } from '../api';
import { isReviewRow, orderAttention } from './attentionOrder';

const OLDER = '2026-11-01T10:00:00Z';
const NEWER = '2026-11-02T10:00:00.123456Z';

function row(
  code: string,
  severity: AttentionItem['severity'],
  subject: string | null,
  since: string | null,
): AttentionItem {
  return { code, severity, subject, message: 'message', action: null, dismiss: null, since };
}

/**
 * The same order the services sort their own feeds in (AttentionOrder.java), applied again after the two feeds
 * merge. Each case is decided by exactly one step, and the code tie-break alone would decide it the other way.
 */
describe('orderAttention', () => {
  it('puts a blocker before a newer review warning', () => {
    const blocker = row('Z_BLOCKER', 'BLOCKING', null, OLDER);
    const review = row('REVIEW_FAILED', 'WARNING', 'TEST-WS/TEST-REPO#1', NEWER);

    expect(orderAttention([review, blocker])).toEqual([blocker, review]);
  });

  it('puts a review warning before a newer warning about something else', () => {
    const review = row('REVIEW_HARNESS_REFUSED', 'WARNING', 'TEST-WS/TEST-REPO#95', OLDER);
    const deadLetter = row('DLQ_PENDING', 'WARNING', null, NEWER);

    expect(orderAttention([deadLetter, review])).toEqual([review, deadLetter]);
  });

  it('puts the newer row first within a group', () => {
    const older = row('A_CONDITION', 'WARNING', null, OLDER);
    const newer = row('Z_CONDITION', 'WARNING', null, NEWER);

    expect(orderAttention([older, newer])).toEqual([newer, older]);
  });

  it('puts a row with no known time after timed rows', () => {
    const untimed = row('A_CONDITION', 'WARNING', null, null);
    const missing: AttentionItem = { ...row('B_CONDITION', 'WARNING', null, null), since: undefined };
    const timed = row('Z_CONDITION', 'WARNING', null, OLDER);

    expect(orderAttention([untimed, missing, timed])).toEqual([timed, untimed, missing]);
  });

  it('falls back to code, then subject, on equal times', () => {
    const laterCode = row('B_CONDITION', 'WARNING', 'a', OLDER);
    const earlierCode = row('A_CONDITION', 'WARNING', 'z', OLDER);
    const subjectB = row('C_CONDITION', 'WARNING', 'b', OLDER);
    const subjectA = row('C_CONDITION', 'WARNING', 'a', OLDER);
    const noSubject = row('C_CONDITION', 'WARNING', null, OLDER);

    expect(orderAttention([subjectB, laterCode, subjectA, earlierCode, noSubject])).toEqual([
      earlierCode,
      laterCode,
      noSubject,
      subjectA,
      subjectB,
    ]);
  });

  /** Code units, as Java's String.compareTo: an underscore sorts after capitals there, and must here too. */
  it('compares codes by code unit, as the services do', () => {
    const underscore = row('A_B', 'WARNING', null, null);
    const letter = row('AB', 'WARNING', null, null);

    expect(orderAttention([underscore, letter])).toEqual([letter, underscore]);
  });

  it('does not reorder the list it was given', () => {
    const older = row('A_CONDITION', 'WARNING', null, OLDER);
    const newer = row('Z_CONDITION', 'WARNING', null, NEWER);
    const given = [older, newer];

    orderAttention(given);

    expect(given).toEqual([older, newer]);
  });
});

describe('isReviewRow', () => {
  it('matches the review codes and nothing that merely starts alike', () => {
    expect(isReviewRow(row('REVIEW_STUCK', 'WARNING', null, null))).toBe(true);
    expect(isReviewRow(row('REVIEW_HARNESS_REFUSED', 'WARNING', null, null))).toBe(true);
    expect(isReviewRow(row('REPOSITORY_NOT_REGISTERED', 'WARNING', null, null))).toBe(false);
  });
});
