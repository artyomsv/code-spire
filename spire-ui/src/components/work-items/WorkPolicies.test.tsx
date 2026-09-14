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
it('opens on visible profile versions with an Add action and no creation form', async () => {
  render(<WorkPolicies />);
  const table = await screen.findByRole('table', { name: 'Profile versions' });
  expect(within(table).getByRole('cell', { name: 'TEST-assisted' })).toBeInTheDocument();
  expect(within(table).getByRole('cell', { name: 'v3' })).toBeInTheDocument();
  expect(screen.queryByLabelText('Profile name')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Add profile' }));
  expect(screen.getByLabelText('Profile name')).toHaveValue('');
});
it('shows a created profile immediately in the list and confirms its version', async () => {
  vi.mocked(api.profiles).mockResolvedValue([]);
  vi.mocked(api.saveProfile).mockResolvedValue({ ...profile, name: 'TEST-visible profile', version: 1 });
  render(<WorkPolicies />);
  expect(await screen.findByText('No profiles yet')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Add profile' }));
  fireEvent.change(screen.getByLabelText('Profile name'), { target: { value: 'TEST-visible profile' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create profile version' }));
  const table = await screen.findByRole('table', { name: 'Profile versions' });
  expect(within(table).getByRole('cell', { name: 'TEST-visible profile' })).toBeInTheDocument();
  expect(within(table).getByRole('cell', { name: 'v1' })).toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('Profile TEST-visible profile v1 created.');
  expect(screen.queryByLabelText('Profile name')).toBeNull();
});
it('opens an existing version from its row and locks every control during save', async () => {
  let finish!: (value: api.Profile) => void;
  vi.mocked(api.saveProfile).mockReturnValue(new Promise(resolve => { finish = resolve; }));
  render(<WorkPolicies />);
  fireEvent.click(await screen.findByRole('button', { name: 'New version of TEST-assisted v3' }));
  expect(screen.getByLabelText('Base profile')).toHaveValue('TEST-profile:3');
  const group = screen.getByRole('group', { name: 'New profile version' });
  fireEvent.click(screen.getByRole('button', { name: 'Create profile version' }));
  for (const control of group.querySelectorAll('input,select,textarea,button')) expect(control).toBeDisabled();
  expect(api.saveProfile).toHaveBeenCalledTimes(1);
  await act(async () => finish({ ...profile, version: 4 }));
  expect(within(screen.getByRole('table', { name: 'Profile versions' })).getByRole('cell', { name: 'v4' })).toBeInTheDocument();
});
it('marks required and optional profile fields and provides their help', async () => {
  render(<WorkPolicies />); await addProfile();
  const group = screen.getByRole('group', { name: 'Add profile' });
  for (const control of group.querySelectorAll('input,select,textarea')) {
    const field = control.closest('label')!;
    expect(field.querySelector('.field-hint')).not.toBeNull();
    expect(field.querySelector('.field-optional')).toHaveTextContent(control.hasAttribute('required') ? 'required' : 'optional');
  }
  expect(screen.getByText('Production verification is unavailable until M4.')).toBeInTheDocument();
  expect(screen.getByText('Production landing is unavailable until M4.')).toBeInTheDocument();
});
it('creates a new immutable version with the full vector and limits', async () => {
  render(<WorkPolicies />);await addProfile();fireEvent.change(await screen.findByLabelText('Base profile'), { target: { value: 'TEST-profile:3' } });
  expect(screen.getByLabelText('Profile name')).toHaveAttribute('readonly');expect(screen.getByLabelText('Precedence')).toHaveAttribute('readonly');
  fireEvent.change(screen.getByLabelText('plan mode'), { target: { value: 'off' } });
  fireEvent.change(screen.getByLabelText('Maximum calls per item'), { target: { value: '21' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create profile version' }));
  await waitFor(() => expect(api.saveProfile).toHaveBeenCalledWith({ ...profile, version: 4, modes: { ...profile.modes, PLAN: 'off' }, limits: { ...profile.limits, maxCallsPerItem: 21 } }));
});
it('pins the selected ceiling and label versions with the displayed revision', async () => {
  render(<WorkPolicies />);fireEvent.change(await screen.findByLabelText('Repository policy'), { target: { value: repository.id } });
  await screen.findByLabelText('Label 1');fireEvent.click(screen.getByRole('button', { name: 'Save repository policy' }));
  await waitFor(() => expect(api.savePolicy).toHaveBeenCalledWith(repository.id, { revision: 7, ceiling: { id: profile.id, version: 3 }, mappings: { 'TEST-work': { id: profile.id, version: 3 } } }));
});
it('refuses duplicate labels instead of silently dropping one mapping', async () => {
  render(<WorkPolicies />);fireEvent.change(await screen.findByLabelText('Repository policy'), { target: { value: repository.id } });
  await screen.findByLabelText('Label 1');fireEvent.click(screen.getByRole('button', { name: 'Add label mapping' }));
  fireEvent.change(screen.getByLabelText('Label 2'), { target: { value: ' TEST-work ' } });
  fireEvent.change(screen.getByLabelText('Label profile 2'), { target: { value: 'TEST-profile:3' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save repository policy' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Each label needs one mapping.');expect(api.savePolicy).not.toHaveBeenCalled();
});
it('keeps a failed policy save visible', async () => {
  vi.mocked(api.savePolicy).mockRejectedValue(new Error('TEST-policy changed; reload'));
  render(<WorkPolicies />);fireEvent.change(await screen.findByLabelText('Repository policy'), { target: { value: repository.id } });
  await screen.findByLabelText('Label 1');fireEvent.click(screen.getByRole('button', { name: 'Save repository policy' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-policy changed; reload');expect(screen.getByLabelText('Label 1')).toHaveValue('TEST-work');
});
it('ignores an earlier repository policy after selecting another repository', async () => {
  vi.mocked(repositories.fetchRepositories).mockResolvedValue([repository, { ...repository, id: 'TEST-other', slug: 'TEST-other' }]);
  let resolve!: (policy: api.Policy) => void;
  vi.mocked(api.policy).mockReturnValueOnce(new Promise(done => { resolve = done; })).mockResolvedValue({ revision: 4, ceiling: profile, mappings: {} });
  render(<WorkPolicies />);fireEvent.change(await screen.findByLabelText('Repository policy'), { target: { value: repository.id } });
  fireEvent.change(screen.getByLabelText('Repository policy'), { target: { value: 'TEST-other' } });
  await waitFor(() => expect(screen.getByLabelText('Repository ceiling')).toHaveValue('TEST-profile:3'));
  await act(async () => { resolve({ revision: 9, ceiling: profile, mappings: { 'TEST-old': profile } }); });
  expect(screen.queryByLabelText('Label 1')).not.toBeInTheDocument();
});
