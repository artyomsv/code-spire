import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { SettingsMemory } from './SettingsMemory';
import * as api from '../api';

/**
 * The Memory screen (P4 / FR-10).
 *
 * <p>Two things must be on screen and both are easy to leave off. The **evidence with its
 * threshold**, because a proposal nobody can weigh is the ADR-026 rung-2 failure recurring — a
 * conclusion drawn from a corpus too thin to speak, invisible because the numbers were never
 * rendered. And the **state a preference is in**, because "proposed" and "approved" differ by
 * whether findings are being hidden from pull requests right now.
 */

const THRESHOLDS = { minEvidence: 10, minDismissedPercent: 75 };

function preference(overrides: Partial<api.LearnedPreference> = {}): api.LearnedPreference {
  return {
    id: 1,
    scopeType: 'repo',
    scopeValue: 'TEST-WS/TEST-REPO',
    category: 'NAMING',
    pathGlob: '**/test/**',
    severity: 'NIT',
    state: 'PROPOSED',
    evidenceTotal: 16,
    evidenceDismissed: 14,
    evidenceReviews: 5,
    ...overrides,
  };
}

describe('SettingsMemory', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('shows the evidence and the bar it had to clear', async () => {
    vi.spyOn(api, 'fetchMemory').mockResolvedValue({
      preferences: [preference()],
      thresholds: THRESHOLDS,
    });

    render(<SettingsMemory />);

    await waitFor(() => expect(screen.getByText(/14 of 16 dismissed/)).toBeTruthy());
    // 88% observed against a 75% bar — both numbers, or the operator is judging blind.
    expect(screen.getByText(/88% across 5 pull requests/)).toBeTruthy();
    expect(screen.getByText(/bar: 10 findings, 75%/)).toBeTruthy();
  });

  it('offers approve and reject on a proposal, and neither hides anything yet', async () => {
    vi.spyOn(api, 'fetchMemory').mockResolvedValue({
      preferences: [preference()],
      thresholds: THRESHOLDS,
    });

    render(<SettingsMemory />);

    await waitFor(() => expect(screen.getByText('Approve')).toBeTruthy());
    expect(screen.getByText('Reject')).toBeTruthy();
    expect(screen.queryByText('Hiding these findings')).toBeNull();
  });

  /** An approved preference is actively changing every review, so the screen has to say so. */
  it('says an approved preference is hiding findings, and offers to stop it', async () => {
    vi.spyOn(api, 'fetchMemory').mockResolvedValue({
      preferences: [preference({ state: 'APPROVED' })],
      thresholds: THRESHOLDS,
    });

    render(<SettingsMemory />);

    await waitFor(() => expect(screen.getByText('Hiding these findings')).toBeTruthy());
    expect(screen.getByText('Stop hiding')).toBeTruthy();
    expect(screen.queryByText('Approve')).toBeNull();
  });

  it('sends the decision the button names', async () => {
    vi.spyOn(api, 'fetchMemory').mockResolvedValue({
      preferences: [preference({ id: 42 })],
      thresholds: THRESHOLDS,
    });
    const decide = vi.spyOn(api, 'decidePreference').mockResolvedValue(undefined);

    render(<SettingsMemory />);
    await waitFor(() => expect(screen.getByText('Approve')).toBeTruthy());
    fireEvent.click(screen.getByText('Approve'));

    await waitFor(() => expect(decide).toHaveBeenCalledWith(42, 'approve'));
  });

  /**
   * An empty list must explain itself. "Nothing here" reads as a broken feature; the truth is that
   * the corpus starts empty by design and a group needs evidence before it can say anything.
   */
  it('explains an empty list rather than leaving it blank', async () => {
    vi.spyOn(api, 'fetchMemory').mockResolvedValue({ preferences: [], thresholds: THRESHOLDS });

    render(<SettingsMemory />);

    await waitFor(() => expect(screen.getByRole('status')).toBeTruthy());
    expect(screen.getByRole('status').textContent).toMatch(/10 judged findings/);
    expect(screen.getByRole('status').textContent).toMatch(/nothing was backfilled/);
  });

  it('reports a failure as an error rather than as an empty list', async () => {
    vi.spyOn(api, 'fetchMemory').mockRejectedValue(new Error('TEST-BOOM'));

    render(<SettingsMemory />);

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.getByRole('alert').textContent).toMatch(/TEST-BOOM/);
  });
});
