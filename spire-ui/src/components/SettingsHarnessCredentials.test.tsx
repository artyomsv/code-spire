import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api';
import type { HarnessCredentialView } from '../api';
import SettingsHarnessCredentials from './SettingsHarnessCredentials';

/**
 * The pool's two exhaustion states are the point of this screen.
 *
 * <p>A rate limit is a promise that capacity returns; a rejection is an answer that it will not, and
 * retrying it burns one request per run to learn nothing. The pool keeps them apart in its schema, and
 * until now an operator could see neither: members were added with `curl` and their state only existed
 * in the database. A screen that rendered both as "unavailable" would put the distinction back where
 * it was.
 */
const member = (over: Partial<HarnessCredentialView> = {}): HarnessCredentialView => ({
  id: 'TEST-credential-1', label: 'TEST-key', type: 'openai', baseUrl: 'https://api.example.test/v1',
  enabled: true, rateLimitedUntil: null, rejectedAt: null, lastUsedAt: '2026-09-16T10:00:00Z', ...over,
});

const row = async (label: string) => (await screen.findByText(label)).closest('tr') as HTMLElement;
/** SettingField renders a visible label AND an aria-label, so a field is addressed by its control. */
const field = { selector: 'input,select,textarea' };

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(api, 'fetchHarnessCredentials').mockResolvedValue([member()]);
  vi.spyOn(api, 'addHarnessCredential').mockResolvedValue(member({ id: 'TEST-credential-2', label: 'TEST-new' }));
  vi.spyOn(api, 'disableHarnessCredential').mockResolvedValue(undefined);
  vi.spyOn(api, 'enableHarnessCredential').mockResolvedValue(undefined);
  vi.spyOn(api, 'clearHarnessCredentialRejection').mockResolvedValue(undefined);
  vi.spyOn(api, 'restHarnessCredential').mockResolvedValue(undefined);
});

it('tells a resting key apart from a refused one, and offers the action each needs', async () => {
  vi.mocked(api.fetchHarnessCredentials).mockResolvedValue([
    member({ id: 'TEST-resting', label: 'TEST-resting', rateLimitedUntil: '2099-01-01T00:00:00Z' }),
    member({ id: 'TEST-rejected', label: 'TEST-rejected', rejectedAt: '2026-09-16T09:00:00Z' }),
  ]);
  render(<SettingsHarnessCredentials />);

  const resting = await row('TEST-resting');
  expect(within(resting).getByText('Resting')).toBeInTheDocument();
  expect(within(resting).queryByRole('button', { name: 'Try again' })).toBeNull();

  const rejected = await row('TEST-rejected');
  expect(within(rejected).getByText('Rejected')).toBeInTheDocument();
  // Only a person clears a rejection: a key that was refused will be refused again.
  expect(within(rejected).getByRole('button', { name: 'Try again' })).toBeEnabled();
  expect(within(rejected).queryByRole('button', { name: 'Rest longer' })).toBeNull();
});

it('switches a member off and back on, and says which one changed', async () => {
  // The list is read again after the change, so the second answer is the switched-off row.
  vi.mocked(api.fetchHarnessCredentials).mockResolvedValueOnce([member()]).mockResolvedValue([member({ enabled: false })]);
  render(<SettingsHarnessCredentials />);
  fireEvent.click(within(await row('TEST-key')).getByRole('button', { name: 'Switch off' }));
  await waitFor(() => expect(api.disableHarnessCredential).toHaveBeenCalledWith('TEST-credential-1'));
  expect(await screen.findByText(/TEST-key is switched off/)).toBeInTheDocument();

  fireEvent.click(await screen.findByRole('button', { name: 'Switch on' }));
  await waitFor(() => expect(api.enableHarnessCredential).toHaveBeenCalledWith('TEST-credential-1'));
});

it('adds a key beside the list and never asks for it again', async () => {
  render(<SettingsHarnessCredentials />);
  fireEvent.click(await screen.findByRole('button', { name: 'Add an API key' }));
  fireEvent.change(screen.getByLabelText('Label', field), { target: { value: 'TEST-new' } });
  fireEvent.change(screen.getByLabelText('Endpoint', field), { target: { value: 'https://api.example.test/v1' } });
  // The key field is a password field: it is write-only at the API too, and no read returns one.
  const key = screen.getByLabelText('Key', field);
  expect(key).toHaveAttribute('type', 'password');
  fireEvent.change(key, { target: { value: 'TEST-secret-value' } });
  fireEvent.click(screen.getByRole('button', { name: 'Add the key' }));

  await waitFor(() => expect(api.addHarnessCredential).toHaveBeenCalledWith({
    label: 'TEST-new', type: 'openai', baseUrl: 'https://api.example.test/v1', apiKey: 'TEST-secret-value',
  }));
  expect(await screen.findByText(/Added TEST-new/)).toBeInTheDocument();
});

it('will not add a key with a field missing', async () => {
  render(<SettingsHarnessCredentials />);
  fireEvent.click(await screen.findByRole('button', { name: 'Add an API key' }));
  fireEvent.change(screen.getByLabelText('Label', field), { target: { value: 'TEST-new' } });
  expect(screen.getByRole('button', { name: 'Add the key' })).toBeDisabled();
  expect(api.addHarnessCredential).not.toHaveBeenCalled();
});

it('says plainly when the pool is empty, because then every run is refused', async () => {
  vi.mocked(api.fetchHarnessCredentials).mockResolvedValue([]);
  render(<SettingsHarnessCredentials />);
  expect(await screen.findByText('No key yet')).toBeInTheDocument();
  expect(screen.getByText(/every factory run is refused before it starts/)).toBeInTheDocument();
});

it('shows the server refusal rather than a status line', async () => {
  vi.mocked(api.fetchHarnessCredentials).mockRejectedValue(new Error('Failed to load the harness credential pool'));
  render(<SettingsHarnessCredentials />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Failed to load the harness credential pool');
});
