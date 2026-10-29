import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as auth from '../../../auth';
import * as land from './landSettingsApi';
import LandStep from './LandStep';
import { landSetup } from './factoryFixtures';
import { queryWrapper } from '../../../test/queryWrapper';

const showStep = (settings = landSetup(), open: string | null = 'land') => render(<LandStep repositoryId="TEST-repository" settings={settings}
  open={open} setOpen={vi.fn()} changed={vi.fn()} reload={vi.fn()} />, { wrapper: queryWrapper() });
const methodValues = (select: HTMLElement) => within(select).getAllByRole('option')
  .map(option => (option as HTMLOptionElement).value).filter(value => value !== '');

beforeEach(() => {
  vi.spyOn(land, 'landOptions').mockResolvedValue({ checked: true, allowed: ['merge', 'squash', 'rebase'] });
});

describe('land step (M4 land, spec §7.1)', () => {
  it('lists only the methods GitHub allows', async () => {
    vi.mocked(land.landOptions).mockResolvedValue({ checked: true, allowed: ['squash', 'rebase'] });
    showStep(landSetup({ revision: 1, mergeMethod: 'squash' }));
    const select = await screen.findByRole('combobox', { name: 'Merge method' });
    await waitFor(() => expect(methodValues(select)).toEqual(['squash', 'rebase']));
    expect(screen.queryByText(/no longer allows/)).toBeNull();
  });

  it('warns when the saved method is no longer allowed', async () => {
    vi.mocked(land.landOptions).mockResolvedValue({ checked: true, allowed: ['merge'] });
    showStep(landSetup({ revision: 2, mergeMethod: 'rebase' }));
    expect(await screen.findByText(/GitHub no longer allows rebase on this repository/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save land setup' })).toBeDisabled();
  });

  it('offers all three, unchecked, when GitHub did not answer', async () => {
    vi.mocked(land.landOptions).mockResolvedValue({ checked: false, allowed: ['merge', 'squash', 'rebase'] });
    showStep();
    expect(await screen.findByText(/GitHub did not say which methods this repository allows/)).toBeInTheDocument();
    expect(methodValues(screen.getByRole('combobox', { name: 'Merge method' }))).toEqual(['merge', 'squash', 'rebase']);
  });

  it("offers the two ticket rules in the operator's words", async () => {
    showStep();
    expect(await screen.findByRole('radio', { name: 'Keep the status and post a comment that the work is merged, with the PR link' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Close the ticket' })).not.toBeChecked();
  });

  it('says the branch is deleted and what 0 attempts means', async () => {
    showStep(landSetup(), null);
    expect(screen.getByText(/The item branch is deleted after the merge/)).toBeInTheDocument();
    expect(screen.getByText(/0 attempts hands every refusal to you/)).toBeInTheDocument();
  });

  it('shows the defaults it lands with before anything is saved', () => {
    showStep(landSetup(), null);
    expect(screen.getByText(/Not saved, so it lands with merge, a comment on the ticket and up to 3 agent fixes/)).toBeInTheDocument();
  });

  it('saves what was chosen against the revision it loaded', async () => {
    const saved = vi.spyOn(land, 'saveLandSettings').mockResolvedValue(landSetup({ revision: 4, mergeMethod: 'squash', ticketRule: 'close', fixAttempts: 0 }));
    showStep(landSetup({ revision: 3 }));
    await waitFor(() => expect(methodValues(screen.getByRole('combobox', { name: 'Merge method' }))).toHaveLength(3));
    fireEvent.change(screen.getByRole('combobox', { name: 'Merge method' }), { target: { value: 'squash' } });
    fireEvent.click(screen.getByRole('radio', { name: 'Close the ticket' }));
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Fix attempts' }), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save land setup' }));
    await waitFor(() => expect(saved).toHaveBeenCalledWith('TEST-repository',
      { expectedRevision: 3, mergeMethod: 'squash', ticketRule: 'close', fixAttempts: 0 }));
  });

  it('will not save attempts outside 0 to 10', async () => {
    showStep();
    fireEvent.change(await screen.findByRole('spinbutton', { name: 'Fix attempts' }), { target: { value: '11' } });
    expect(screen.getByRole('button', { name: 'Save land setup' })).toBeDisabled();
    expect(screen.getByText(/Fix attempts must be a whole number from 0 to 10/)).toBeInTheDocument();
  });

  it('a stale save shows the reason sentence', async () => {
    vi.spyOn(auth, 'apiFetch').mockResolvedValue(new Response(JSON.stringify({ reason: 'land_settings_changed' }), { status: 409 }));
    showStep(landSetup({ revision: 1 }));
    await waitFor(() => expect(methodValues(screen.getByRole('combobox', { name: 'Merge method' }))).toHaveLength(3));
    fireEvent.click(screen.getByRole('button', { name: 'Save land setup' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Someone else saved the land setup while this form was open. Reload it and try again.');
    expect(alert).not.toHaveTextContent('{');
    expect(screen.getByRole('button', { name: 'Reload the saved land setup' })).toBeInTheDocument();
  });
});
