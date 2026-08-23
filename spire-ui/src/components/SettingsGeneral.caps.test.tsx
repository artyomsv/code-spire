import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SettingsGeneral from './SettingsGeneral';
import * as api from '../api';

/**
 * The Limits section (Task 9 of the spend-caps plan). The one thing this file exists to pin: a blank
 * field must send `null`, never the `0` that `Number('')` produces — ADR-023's whole cost-accounting
 * rework traces back to exactly that coercion, and here `0` means "cap of zero", refusing every
 * review, while `null` means "no cap".
 */
describe('SettingsGeneral — Limits', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'getReviewSettings').mockResolvedValue({ maxAttempts: 3, backoffBaseMs: 5000, backoffFactor: 2 });
    vi.spyOn(api, 'getConversationSettings').mockResolvedValue({
      level: 'REPORT_ONLY', turnCap: 4, maxAttempts: 5, backoffBaseMs: 2000, backoffFactor: 2,
    });
  });

  function fieldInput(label: string): HTMLInputElement {
    const button = screen.getByRole('button', { name: `About ${label.toLowerCase()} — limits` });
    const wrapper = button.closest('label');
    if (!wrapper) throw new Error(`Expected an accessible label wrapping "${label}"`);
    return within(wrapper).getByRole('spinbutton');
  }

  it('shows the Limits section beside Code review and Conversation', async () => {
    vi.spyOn(api, 'getCapSettings').mockResolvedValue({
      maxChangedFiles: null, maxDiffBytes: null, spendCapMillicents: null, callCap: null, windowMinutes: 1440,
    });
    render(<SettingsGeneral />);
    await screen.findByText('Limits');
    expect(screen.getByText('Code review')).toBeTruthy();
    expect(screen.getByText('Conversation')).toBeTruthy();
  });

  it('round-trips a stored value for every limit field', async () => {
    vi.spyOn(api, 'getCapSettings').mockResolvedValue({
      maxChangedFiles: 500, maxDiffBytes: 900_000, spendCapMillicents: 500_000, callCap: 100, windowMinutes: 60,
    });
    render(<SettingsGeneral />);
    await screen.findByText('Limits');

    expect(fieldInput('Max changed files').value).toBe('500');
    expect(fieldInput('Max diff bytes').value).toBe('900000');
    expect(fieldInput('Spend cap (millicents)').value).toBe('500000');
    expect(fieldInput('Call cap').value).toBe('100');
    expect(fieldInput('Window (minutes)').value).toBe('60');
  });

  it('sends null when a stored limit is cleared back to blank', async () => {
    vi.spyOn(api, 'getCapSettings').mockResolvedValue({
      maxChangedFiles: 500, maxDiffBytes: null, spendCapMillicents: null, callCap: null, windowMinutes: 1440,
    });
    const putCaps = vi.spyOn(api, 'setCapSettings').mockResolvedValue({
      maxChangedFiles: null, maxDiffBytes: null, spendCapMillicents: null, callCap: null, windowMinutes: 1440,
    });
    render(<SettingsGeneral />);
    await screen.findByText('Limits');

    fireEvent.change(fieldInput('Max changed files'), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(putCaps).toHaveBeenCalledWith({
        maxChangedFiles: null,
        maxDiffBytes: null,
        spendCapMillicents: null,
        callCap: null,
        windowMinutes: 1440,
      }),
    );
  });

  it('rejects an entered 0 without sending it', async () => {
    vi.spyOn(api, 'getCapSettings').mockResolvedValue({
      maxChangedFiles: null, maxDiffBytes: null, spendCapMillicents: null, callCap: null, windowMinutes: 1440,
    });
    const putCaps = vi.spyOn(api, 'setCapSettings');
    render(<SettingsGeneral />);
    await screen.findByText('Limits');

    fireEvent.change(fieldInput('Call cap'), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await screen.findByText(/call cap must be a positive whole number/i);
    expect(putCaps).not.toHaveBeenCalled();
  });

  it('rejects a 0 without saving the OTHER groups either, since there is one Save for the whole page', async () => {
    vi.spyOn(api, 'getCapSettings').mockResolvedValue({
      maxChangedFiles: null, maxDiffBytes: null, spendCapMillicents: null, callCap: null, windowMinutes: 1440,
    });
    const putReview = vi.spyOn(api, 'setReviewSettings');
    render(<SettingsGeneral />);
    await screen.findByText('Limits');

    // Touch a field in the Code review group too, so this test would fail if the caps validation
    // gate did not run BEFORE any of the three PUTs are issued.
    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '9' } });
    fireEvent.change(fieldInput('Spend cap (millicents)'), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await screen.findByText(/spend cap \(millicents\) must be a positive whole number/i);
    expect(putReview).not.toHaveBeenCalled();
  });
});
