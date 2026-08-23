import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SettingsGeneral from './SettingsGeneral';
import * as api from '../api';

/**
 * One Save for both groups: the page holds two endpoints, which an operator should not have to notice
 * as two buttons — and an untouched group must not be rewritten just because its sibling changed.
 */
describe('SettingsGeneral', () => {
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

  it('shows a single Save for both groups', async () => {
    render(<SettingsGeneral />);
    await screen.findByText('Code review');
    expect(screen.getByText('Conversation')).toBeTruthy();
    expect(screen.getAllByRole('button', { name: 'Save' })).toHaveLength(1);
  });

  it('writes only the group that changed', async () => {
    const putReview = vi.spyOn(api, 'setReviewSettings').mockResolvedValue({ maxAttempts: 6, backoffBaseMs: 5000, backoffFactor: 2 });
    const putConversation = vi.spyOn(api, 'setConversationSettings');
    render(<SettingsGeneral />);
    await screen.findByText('Code review');

    // The review group's only number input is its retry budget.
    const reviewAttempts = screen.getAllByRole('spinbutton')[0];
    fireEvent.change(reviewAttempts, { target: { value: '6' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(putReview).toHaveBeenCalledWith({ maxAttempts: 6, backoffBaseMs: 5000, backoffFactor: 2 }));
    expect(putConversation).not.toHaveBeenCalled();
  });

  it('surfaces a save failure without claiming success', async () => {
    vi.spyOn(api, 'setReviewSettings').mockRejectedValue(new Error('boom'));
    render(<SettingsGeneral />);
    await screen.findByText('Code review');

    const reviewAttempts = screen.getAllByRole('spinbutton')[0];
    fireEvent.change(reviewAttempts, { target: { value: '7' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await screen.findByText('boom');
    expect(screen.queryByText('Saved')).toBeNull();
  });

  it('explains each field through an info control rather than prose', async () => {
    render(<SettingsGeneral />);
    await screen.findByText('Code review');
    // Keyboard-reachable, not hover-only.
    expect(screen.getByRole('button', { name: 'About retry attempts — code review' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'About turn cap — conversation' })).toBeTruthy();
  });
});

describe('clampToViewport', () => {
  it('keeps a wide bubble inside both edges', async () => {
    const { clampToViewport } = await import('./Tooltip');
    // A trigger hugging the left edge: the centre is pushed right so the bubble's left half fits.
    expect(clampToViewport(10, 1200)).toBe(182);
    // …and the mirror case on the right.
    expect(clampToViewport(1190, 1200)).toBe(1018);
    // Comfortably central triggers are untouched.
    expect(clampToViewport(600, 1200)).toBe(600);
  });

  it('gives up rather than fighting itself on a very narrow viewport', async () => {
    const { clampToViewport } = await import('./Tooltip');
    expect(clampToViewport(120, 300)).toBe(120); // CSS max-width caps the bubble instead
  });
});
