import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import * as api from './workPolicyApi';
import * as repositories from '../repositories/repositoriesApi';
import WorkPolicies from './WorkPolicies';

const repository: repositories.Repository = { id: 'TEST-repository', scmType: 'github', forgeOrigin: 'https://github.example.test', workspace: 'TEST-owner', slug: 'TEST-repo', enabled: true, revision: 1, reviewer: null, factory: null };
const profile: api.Profile = { id: 'TEST-profile', name: 'TEST-assisted', version: 3, precedence: 10,
  modes: { INTAKE: 'auto', SPEC: 'auto', PLAN: 'approve', BUILD: 'auto', VERIFY: 'auto', DELIVER: 'draft_pr', REVIEW: 'auto', LAND: 'approve' },
  limits: { gateTtlSeconds: 3600, maxRunsPerItem: 5, maxStepsPerPlan: 20, maxWallClockSeconds: 7200, maxCostMillicents: 2_000_000, maxCallsPerItem: 40, protectedPaths: ['TEST-sensitive/**'] } };
beforeEach(() => {
  openInfo = null;
  vi.spyOn(repositories, 'fetchRepositories').mockResolvedValue([repository]);
  vi.spyOn(api, 'profiles').mockResolvedValue([profile]);
  vi.spyOn(api, 'policy').mockResolvedValue({ revision: 7, ceiling: profile, mappings: { 'TEST-work': profile } });
  vi.spyOn(api, 'savePolicy').mockResolvedValue({ revision: 8, ceiling: profile, mappings: {} });
  vi.spyOn(api, 'saveProfile').mockResolvedValue({ ...profile, version: 4 });
});
async function addProfile() {
  const button = await screen.findByRole('button', { name: 'Add profile' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
}
/** A SettingField carries its explanation on an info control; focusing it reveals the tooltip. */
let openInfo: HTMLElement | null = null;
function hintOf(label: string, scope: string) {
  if (openInfo) fireEvent.blur(openInfo);
  const info = screen.getByRole('button', { name: `About ${label.toLowerCase()} — ${scope}` });
  fireEvent.focus(info);
  openInfo = info;
  return screen.getByRole('tooltip');
}
it('opens on visible profile versions with an Add action and no creation form', async () => {
  render(<WorkPolicies />);
  const table = await screen.findByRole('table', { name: 'Profile versions' });
  expect(within(table).getByRole('cell', { name: 'TEST-assisted' })).toBeInTheDocument();
  expect(within(table).getByRole('cell', { name: 'v3' })).toBeInTheDocument();
  expect(screen.queryByLabelText('Profile name', { selector: 'input,select,textarea' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Add profile' }));
  expect(screen.getByLabelText('Profile name', { selector: 'input,select,textarea' })).toHaveValue('');
});
it('shows a created profile immediately in the list and confirms its version', async () => {
  vi.mocked(api.profiles).mockResolvedValue([]);
  vi.mocked(api.saveProfile).mockResolvedValue({ ...profile, name: 'TEST-visible profile', version: 1 });
  render(<WorkPolicies />);
  expect(await screen.findByText('No profiles yet')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Add profile' }));
  fireEvent.change(screen.getByLabelText('Profile name', { selector: 'input,select,textarea' }), { target: { value: 'TEST-visible profile' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
  const table = await screen.findByRole('table', { name: 'Profile versions' });
  expect(within(table).getByRole('cell', { name: 'TEST-visible profile' })).toBeInTheDocument();
  expect(within(table).getByRole('cell', { name: 'v1' })).toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('Profile TEST-visible profile v1 created.');
  expect(screen.queryByLabelText('Profile name', { selector: 'input,select,textarea' })).toBeNull();
});
it('opens an existing version from its row and locks every control during save', async () => {
  let finish!: (value: api.Profile) => void;
  vi.mocked(api.saveProfile).mockReturnValue(new Promise(resolve => { finish = resolve; }));
  render(<WorkPolicies />);
  fireEvent.click(await screen.findByRole('button', { name: 'New version of TEST-assisted v3' }));
  expect(screen.getByLabelText('Base profile', { selector: 'input,select,textarea' })).toHaveValue('TEST-profile:3');
  const group = screen.getByRole('group', { name: 'New version of TEST-assisted v3' });
  fireEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
  for (const control of group.querySelectorAll('input,select,textarea,button')) expect(control).toBeDisabled();
  expect(api.saveProfile).toHaveBeenCalledTimes(1);
  await act(async () => finish({ ...profile, version: 4 }));
  expect(within(screen.getByRole('table', { name: 'Profile versions' })).getByRole('cell', { name: 'v4' })).toBeInTheDocument();
});
it('marks required and optional profile fields and provides their help', async () => {
  render(<WorkPolicies />); await addProfile();
  // The editor renders in the shared side panel, not inline beside the list it changes.
  expect(screen.getByRole('group', { name: 'Add profile' })).toHaveClass('panel-form');
  expect(hintOf('Profile name', 'profile')).toHaveTextContent('Required.');
  expect(hintOf('Base profile', 'profile')).toHaveTextContent('Optional.');
  expect(hintOf('verify mode', 'phase modes')).toHaveTextContent('Production verification is unavailable until M4.');
  expect(hintOf('land mode', 'phase modes')).toHaveTextContent('Production landing is unavailable until M4.');
});
it('creates a new immutable version with the full vector and limits', async () => {
  render(<WorkPolicies />);await addProfile();fireEvent.change(await screen.findByLabelText('Base profile', { selector: 'input,select,textarea' }), { target: { value: 'TEST-profile:3' } });
  expect(screen.getByLabelText('Profile name', { selector: 'input,select,textarea' })).toHaveAttribute('readonly');expect(screen.getByLabelText('Precedence', { selector: 'input,select,textarea' })).toHaveAttribute('readonly');
  fireEvent.change(screen.getByLabelText('plan mode', { selector: 'input,select,textarea' }), { target: { value: 'off' } });
  fireEvent.change(screen.getByLabelText('Maximum calls per item', { selector: 'input,select,textarea' }), { target: { value: '21' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save as new version' }));
  await waitFor(() => expect(api.saveProfile).toHaveBeenCalledWith({ ...profile, version: 4, modes: { ...profile.modes, PLAN: 'off' }, limits: { ...profile.limits, maxCallsPerItem: 21 } }));
});
it('names the repositories that pin each version, as ceiling and by label', async () => {
  vi.mocked(repositories.fetchRepositories).mockResolvedValue([repository, { ...repository, id: 'TEST-other', slug: 'TEST-other' }]);
  vi.mocked(api.policy).mockImplementation(async (id): Promise<api.Policy> => id === repository.id
    ? { revision: 7, ceiling: profile, mappings: { 'TEST-work': profile } }
    : { revision: 2, ceiling: null, mappings: {} });
  render(<WorkPolicies />);
  const row = within(await screen.findByRole('table', { name: 'Profile versions' })).getAllByRole('row')[1];
  expect(await within(row).findByText('TEST-owner/TEST-repo')).toBeInTheDocument();
  expect(within(row).getByText('ceiling · label TEST-work')).toBeInTheDocument();
  // A repository that pins nothing is not listed as a user.
  expect(within(row).queryByText('TEST-owner/TEST-other')).toBeNull();
});
it('does not count a version the repository does not pin', async () => {
  vi.mocked(api.profiles).mockResolvedValue([profile, { ...profile, version: 4 }]);
  render(<WorkPolicies />);
  const table = await screen.findByRole('table', { name: 'Profile versions' });
  const current = within(table).getAllByRole('row')[1];
  await waitFor(() => expect(api.policy).toHaveBeenCalled());
  expect(within(current).getByText('v4')).toBeInTheDocument();
  expect(within(current).getByText('—')).toBeInTheDocument();
});
it('files a slow repository policy under the repository it was asked about', async () => {
  vi.mocked(repositories.fetchRepositories).mockResolvedValue([repository, { ...repository, id: 'TEST-other', slug: 'TEST-other' }]);
  let resolve!: (policy: api.Policy) => void;
  vi.mocked(api.policy).mockReturnValueOnce(new Promise(done => { resolve = done; })).mockResolvedValue({ revision: 4, ceiling: profile, mappings: {} });
  render(<WorkPolicies />);
  const row = within(await screen.findByRole('table', { name: 'Profile versions' })).getAllByRole('row')[1];
  // The second repository answered first; the first is still outstanding.
  expect(await within(row).findByText('TEST-owner/TEST-other')).toBeInTheDocument();
  expect(within(row).queryByText('TEST-owner/TEST-repo')).toBeNull();
  await act(async () => { resolve({ revision: 9, ceiling: null, mappings: { 'TEST-late': profile } }); });
  const uses = (repo: string) => within(row).getByText(repo).parentElement!.querySelector('.prov-sub');
  expect(uses('TEST-owner/TEST-repo')).toHaveTextContent('label TEST-late');
  // The late answer must not have reached the repository that never asked for it.
  expect(uses('TEST-owner/TEST-other')).toHaveTextContent(/^ceiling$/);
});
