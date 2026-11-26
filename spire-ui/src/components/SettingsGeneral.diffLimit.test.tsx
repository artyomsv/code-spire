import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SettingsGeneral from './SettingsGeneral';
import * as api from '../api';
import * as diffLimitApi from '../reviewDiffLimitApi';
import { queryWrapper } from '../test/queryWrapper';
import { INVALID_STORED_MESSAGE } from './ReviewDiffLimitField';

/**
 * The Review diff limit (review input limit design, 2026-11-26). Blank means "as much as the model allows" and
 * must send `null`, never the `0` that `Number('')` produces; a number outside 24,000 to 2,000,000 is refused
 * before anything is sent.
 */
describe('SettingsGeneral — Review diff limit', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'getReviewSettings').mockResolvedValue({ maxAttempts: 3, backoffBaseMs: 5000, backoffFactor: 2 });
    vi.spyOn(api, 'getConversationSettings').mockResolvedValue({
      level: 'REPORT_ONLY', turnCap: 4, maxAttempts: 5, backoffBaseMs: 2000, backoffFactor: 2,
    });
    vi.spyOn(api, 'getCapSettings').mockResolvedValue({
      maxChangedFiles: null, maxDiffBytes: null, spendCapMillicents: null, callCap: null, windowMinutes: 1440,
    });
  });

  /** Found by its own accessible name, as a screen reader names it: the visible label, not the info button. */
  function limitInput(): HTMLInputElement {
    return screen.getByRole('spinbutton', { name: 'Review diff limit (tokens)' }) as HTMLInputElement;
  }

  it('shows a stored limit, and blank when unset', async () => {
    vi.spyOn(diffLimitApi, 'getReviewDiffLimit').mockResolvedValue({ limitTokens: 90_000 });
    render(<SettingsGeneral />, { wrapper: queryWrapper() });
    await screen.findByText('Code review');

    expect(limitInput().value).toBe('90000');
  });

  it('saves a typed limit', async () => {
    vi.spyOn(diffLimitApi, 'getReviewDiffLimit').mockResolvedValue({ limitTokens: null });
    const put = vi.spyOn(diffLimitApi, 'setReviewDiffLimit').mockResolvedValue({ limitTokens: 90_000 });
    const putReview = vi.spyOn(api, 'setReviewSettings');
    render(<SettingsGeneral />, { wrapper: queryWrapper() });
    await screen.findByText('Code review');

    fireEvent.change(limitInput(), { target: { value: '90000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(put).toHaveBeenCalledWith({ limitTokens: 90_000 }));
    expect(await screen.findByText('Saved')).toBeInTheDocument();
    expect(putReview).not.toHaveBeenCalled();
  });

  it('sends null when a stored limit is cleared back to blank', async () => {
    vi.spyOn(diffLimitApi, 'getReviewDiffLimit').mockResolvedValue({ limitTokens: 90_000 });
    const put = vi.spyOn(diffLimitApi, 'setReviewDiffLimit').mockResolvedValue({ limitTokens: null });
    render(<SettingsGeneral />, { wrapper: queryWrapper() });
    await screen.findByText('Code review');

    fireEvent.change(limitInput(), { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(put).toHaveBeenCalledWith({ limitTokens: null }));
    expect(limitInput().value).toBe('');
  });

  it('clears an invalid stored limit with a blank Save', async () => {
    vi.spyOn(diffLimitApi, 'getReviewDiffLimit').mockResolvedValue({ limitTokens: null, invalidStoredValue: true });
    const put = vi.spyOn(diffLimitApi, 'setReviewDiffLimit')
      .mockResolvedValue({ limitTokens: null, invalidStoredValue: false });
    render(<SettingsGeneral />, { wrapper: queryWrapper() });
    await screen.findByText('Code review');

    expect(screen.getByText(INVALID_STORED_MESSAGE)).toBeInTheDocument();
    expect(limitInput().value).toBe('');
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(put).toHaveBeenCalledWith({ limitTokens: null }));
    expect(await screen.findByText('Saved')).toBeInTheDocument();
    expect(screen.queryByText(INVALID_STORED_MESSAGE)).not.toBeInTheDocument();
  });

  it('refuses a limit below today’s budget without sending anything', async () => {
    vi.spyOn(diffLimitApi, 'getReviewDiffLimit').mockResolvedValue({ limitTokens: null });
    const put = vi.spyOn(diffLimitApi, 'setReviewDiffLimit');
    render(<SettingsGeneral />, { wrapper: queryWrapper() });
    await screen.findByText('Code review');

    fireEvent.change(limitInput(), { target: { value: '1000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText(/Review diff limit must be a whole number/)).toBeInTheDocument();
    expect(put).not.toHaveBeenCalled();
  });

  it('leaves the limit alone when only another group changed', async () => {
    vi.spyOn(diffLimitApi, 'getReviewDiffLimit').mockResolvedValue({ limitTokens: 90_000 });
    const put = vi.spyOn(diffLimitApi, 'setReviewDiffLimit');
    vi.spyOn(api, 'setReviewSettings').mockResolvedValue({ maxAttempts: 6, backoffBaseMs: 5000, backoffFactor: 2 });
    render(<SettingsGeneral />, { wrapper: queryWrapper() });
    await screen.findByText('Code review');

    fireEvent.change(screen.getAllByRole('spinbutton')[0], { target: { value: '6' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('Saved')).toBeInTheDocument();
    expect(put).not.toHaveBeenCalled();
  });

  it('keeps the info button separately named', async () => {
    vi.spyOn(diffLimitApi, 'getReviewDiffLimit').mockResolvedValue({ limitTokens: null });
    render(<SettingsGeneral />, { wrapper: queryWrapper() });
    await screen.findByText('Code review');

    expect(screen.getByRole('button', { name: 'About review diff limit (tokens) — code review' })).toBeInTheDocument();
    expect(limitInput().id).not.toBe('');
  });

  it('advises 60,000 and says the cap is on the diff only', async () => {
    vi.spyOn(diffLimitApi, 'getReviewDiffLimit').mockResolvedValue({ limitTokens: null });
    render(<SettingsGeneral />, { wrapper: queryWrapper() });
    await screen.findByText('Code review');

    fireEvent.focus(screen.getByRole('button', { name: 'About review diff limit (tokens) — code review' }));

    const hint = (await screen.findByRole('tooltip')).textContent ?? '';
    expect(hint).toContain('about 60,000');
    expect(hint).toContain('diff only, not on the whole prompt');
    expect(hint).not.toContain('90,000');
  });
});
