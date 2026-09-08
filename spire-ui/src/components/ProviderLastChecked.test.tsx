import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import LastChecked from './LastCheckedBadge';
import { lastCheckedLabel, lastCheckedTitle } from './lastChecked';

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

  /**
   * The label is the date alone and the tooltip carries the time. The clock time is what pushed
   * every Accounts row onto a second line, so a label that grows one back is the regression.
   */
  it('reports a passing check with its date, keeping the time for the tooltip', () => {
    const item = { lastCheckAt: '2026-07-27T10:00:00Z', lastCheckOk: true, lastCheckError: null };
    const label = lastCheckedLabel(item);
    const title = lastCheckedTitle(item);

    expect(label).toContain('Checked');
    expect(label).not.toContain('rejected');
    expect(label).toContain(new Date(item.lastCheckAt).toLocaleDateString());
    expect(label).not.toMatch(/\d:\d\d/);
    expect(title).toMatch(/\d:\d\d/);
  });

  /** Never checked has no timestamp to enlarge on, so the tooltip must be absent, not a repeat. */
  it('gives no tooltip when a credential has never been checked', () => {
    expect(lastCheckedTitle({ lastCheckAt: null, lastCheckOk: null, lastCheckError: null })).toBe('');
  });

  it('surfaces the stored reason on a failing check', () => {
    const label = lastCheckedLabel({
      lastCheckAt: '2026-07-27T10:00:00Z',
      lastCheckOk: false,
      lastCheckError: 'Authentication rejected (HTTP 401)',
    });
    expect(label).toContain('Authentication rejected (HTTP 401)');
  });

  /**
   * The badge is bounded and ellipses, so on a rejection the tooltip is the only place the whole
   * message can be read. A title that carried only a longer date would lose the reason exactly
   * where the operator needs it.
   */
  it('carries the whole failure — time of day and reason — on the tooltip', () => {
    const title = lastCheckedTitle({
      lastCheckAt: '2026-07-27T10:00:00Z',
      lastCheckOk: false,
      lastCheckError: 'Authentication rejected (HTTP 401)',
    });

    expect(title).toContain('Authentication rejected (HTTP 401)');
    expect(title).toMatch(/\d:\d\d/);
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

/**
 * The badge is where the split has to hold. Moving the clock time out of the label buys nothing if
 * the component never renders the tooltip that now carries it — the time would simply be gone.
 */
describe('LastChecked badge', () => {
  it('shows the date and hangs the full timestamp on the tooltip', () => {
    const item = { lastCheckAt: '2026-07-27T10:00:00Z', lastCheckOk: true, lastCheckError: null };
    render(<LastChecked item={item} />);

    const badge = screen.getByText(lastCheckedLabel(item));
    expect(badge).toHaveAttribute('title', lastCheckedTitle(item));
    expect(badge.getAttribute('title')).toMatch(/\d:\d\d/);
  });
});
