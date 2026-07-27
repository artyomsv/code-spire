import { describe, expect, it } from 'vitest';
import { lastCheckedLabel } from './lastChecked';

/**
 * "Never checked" is information, not a problem — which is exactly why it lives here and not
 * as an attention row. The three states must stay visually distinct.
 */
describe('lastCheckedLabel', () => {
  it('says so when a credential has never been checked', () => {
    expect(lastCheckedLabel({ lastCheckAt: null, lastCheckOk: null, lastCheckError: null })).toBe(
      'Never checked',
    );
  });

  it('reports a passing check with its time', () => {
    const label = lastCheckedLabel({
      lastCheckAt: '2026-07-27T10:00:00Z',
      lastCheckOk: true,
      lastCheckError: null,
    });
    expect(label).toContain('Checked');
    expect(label).not.toContain('rejected');
  });

  it('surfaces the stored reason on a failing check', () => {
    const label = lastCheckedLabel({
      lastCheckAt: '2026-07-27T10:00:00Z',
      lastCheckOk: false,
      lastCheckError: 'Authentication rejected (HTTP 401)',
    });
    expect(label).toContain('Authentication rejected (HTTP 401)');
  });

  /** A false with no stored detail must still read as a failure, not as a blank. */
  it('reports a failing check with no detail as rejected', () => {
    const label = lastCheckedLabel({
      lastCheckAt: '2026-07-27T10:00:00Z',
      lastCheckOk: false,
      lastCheckError: null,
    });
    expect(label.toLowerCase()).toContain('rejected');
  });
});
