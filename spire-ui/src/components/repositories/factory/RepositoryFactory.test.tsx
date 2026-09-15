import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as sources from '../../work-items/workSourcesApi';
import * as policies from '../../work-items/workPolicyApi';
import RepositoryFactory from './RepositoryFactory';
import { account, policy, profile, repository, source } from './factoryFixtures';

const field = { selector: 'input,select,textarea' };
const renderFactory = (repo = repository) => render(<RepositoryFactory repository={repo} accounts={[account()]}
  webhooks={{ hooks: [], unavailable: false, changed: vi.fn() }} onChanged={vi.fn()} />);
const step = (number: number) => screen.findByRole('listitem', { name: new RegExp(`^Step ${number}:`) });
beforeEach(() => {
  vi.spyOn(sources, 'fetchWorkSources').mockResolvedValue([source()]);
  vi.spyOn(sources, 'saveWorkActor').mockResolvedValue(source());
  vi.spyOn(sources, 'removeWorkActor').mockResolvedValue(source());
  vi.spyOn(policies, 'profiles').mockResolvedValue([profile]);
  vi.spyOn(policies, 'policy').mockResolvedValue(policy());
  vi.spyOn(policies, 'savePolicy').mockResolvedValue(policy({ revision: 8 }));
  vi.spyOn(policies, 'saveProfile').mockImplementation(async value => value);
});

describe('outcome', () => {
  it('says what a complete setup does, naming the person, label, profile and ceiling', async () => {
    renderFactory();
    const outcome = (await screen.findByText('Ready — tickets can start work')).closest('.factory-outcome') as HTMLElement;
    expect(outcome).toHaveTextContent('When @TEST-person adds one of these labels to an open ticket in GitHub issues in TEST-owner/TEST-repo');
    expect(outcome).toHaveTextContent('never more than the ceiling TEST-assisted v3');
    expect(within(outcome).getByText('TEST-work')).toBeInTheDocument();
  });
  it('names the first missing part rather than a later one', async () => {
    vi.mocked(sources.fetchWorkSources).mockResolvedValue([source({ allowedPeople: [] })]);
    vi.mocked(policies.policy).mockResolvedValue(policy({ ceiling: null, mappings: {} }));
    renderFactory();
    expect(await screen.findByText(/nobody is allowed to start work, so every label is ignored/)).toBeInTheDocument();
    expect(within(await step(2)).getByText('nobody yet')).toBeInTheDocument();
  });
  it('ignores a load that answers after the panel moved to another repository', async () => {
    let answer!: (value: sources.WorkSource[]) => void;
    vi.mocked(sources.fetchWorkSources).mockReturnValueOnce(new Promise(done => { answer = done; })).mockResolvedValue([]);
    const view = renderFactory();
    view.rerender(<RepositoryFactory repository={{ ...repository, id: 'TEST-other', slug: 'TEST-other' }} accounts={[account()]}
      webhooks={{ hooks: [], unavailable: false, changed: vi.fn() }} onChanged={vi.fn()} />);
    expect(await screen.findByText(/Nothing reads tickets for this repository yet/)).toBeInTheDocument();
    await act(async () => answer([source({ repositoryId: 'TEST-other', name: 'TEST-stale source' })]));
    expect(screen.queryByText('TEST-stale source')).toBeNull();
  });
});

describe('people', () => {
  async function openPeople() {
    renderFactory();
    fireEvent.click(within(await step(2)).getByRole('button', { name: 'Add a person to TEST-source name' }));
    return screen.getByRole('group', { name: 'Allow a person on TEST-source name' });
  }
  it('names allowed people by handle and keeps the id that authorises them', async () => {
    renderFactory();
    const people = within(await step(2));
    expect(people.getByText('@TEST-person')).toBeInTheDocument();
    expect(people.getByText('900123')).toHaveClass('prov-sub');
    fireEvent.click(people.getByRole('button', { name: 'Remove @TEST-person' }));
    await waitFor(() => expect(sources.removeWorkActor).toHaveBeenCalledWith(source(), '900123'));
  });
  it('allows the one person a lookup finds, naming them on the button, with the source revision', async () => {
    vi.spyOn(sources, 'resolveWorkActor').mockResolvedValue({ status: 'FOUND', actors: [{ providerUserId: '900456', handle: 'TEST-new', displayName: 'TEST-new' }], detail: null });
    const form = await openPeople();
    fireEvent.change(within(form).getByLabelText('Person', field), { target: { value: 'TEST-new' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Find person' }));
    fireEvent.click(await within(form).findByRole('button', { name: 'Allow @TEST-new' }));
    await waitFor(() => expect(sources.saveWorkActor).toHaveBeenCalledWith(source(), 'TEST-new', '900456'));
  });
  it('requires an explicit choice when several people match', async () => {
    vi.spyOn(sources, 'resolveWorkActor').mockResolvedValue({ status: 'FOUND', actors: [
      { providerUserId: '900456', handle: 'TEST-a', displayName: 'TEST-a' }, { providerUserId: '900789', handle: 'TEST-b', displayName: 'TEST-b' }], detail: null });
    const form = await openPeople();
    fireEvent.change(within(form).getByLabelText('Person', field), { target: { value: 'TEST-a' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Find person' }));
    const pick = await within(form).findByLabelText('Resolved source person', field);
    expect(within(form).getByRole('button', { name: 'Allow person' })).toBeDisabled();
    fireEvent.change(pick, { target: { value: '900789' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Allow @TEST-b' }));
    await waitFor(() => expect(sources.saveWorkActor).toHaveBeenCalledWith(source(), 'TEST-a', '900789'));
  });
  it('requires an explicit Jira account choice even for a single match', async () => {
    vi.mocked(sources.fetchWorkSources).mockResolvedValue([source({ type: 'JIRA', name: 'TEST-source name' })]);
    vi.spyOn(sources, 'resolveWorkActor').mockResolvedValue({ status: 'SELECTION_REQUIRED', actors: [{ providerUserId: '900123', handle: '', displayName: 'TEST-person' }], detail: 'Select an account explicitly.' });
    const form = await openPeople();
    fireEvent.change(within(form).getByLabelText('Person', field), { target: { value: 'TEST-person' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Find person' }));
    await within(form).findByLabelText('Resolved source person', field);
    expect(within(form).getByRole('button', { name: 'Allow person' })).toBeDisabled();
  });
  it('discards a found person when the typed handle changes', async () => {
    vi.spyOn(sources, 'resolveWorkActor').mockResolvedValue({ status: 'FOUND', actors: [{ providerUserId: '900456', handle: 'TEST-new', displayName: 'TEST-new' }], detail: null });
    const form = await openPeople();
    fireEvent.change(within(form).getByLabelText('Person', field), { target: { value: 'TEST-new' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Find person' }));
    await within(form).findByRole('button', { name: 'Allow @TEST-new' });
    fireEvent.change(within(form).getByLabelText('Person', field), { target: { value: 'TEST-someone-else' } });
    expect(within(form).getByRole('button', { name: 'Allow person' })).toBeDisabled();
  });
  it('ignores a lookup that answers after the handle was edited', async () => {
    // The form's fieldset stops typing mid-lookup in a browser; this holds if that lock is ever loosened.
    let answer!: (value: Awaited<ReturnType<typeof sources.resolveWorkActor>>) => void;
    vi.spyOn(sources, 'resolveWorkActor').mockReturnValue(new Promise(done => { answer = done; }));
    const form = await openPeople();
    fireEvent.change(within(form).getByLabelText('Person', field), { target: { value: 'TEST-new' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Find person' }));
    fireEvent.change(within(form).getByLabelText('Person', field), { target: { value: 'TEST-someone-else' } });
    await act(async () => answer({ status: 'FOUND', actors: [{ providerUserId: '900456', handle: 'TEST-new', displayName: 'TEST-new' }], detail: null }));
    expect(within(form).queryByText('@TEST-new')).toBeNull();
    expect(within(form).getByRole('button', { name: 'Allow person' })).toBeDisabled();
  });
  it('does not turn a lookup that answers after Cancel into a selection', async () => {
    let answer!: (value: Awaited<ReturnType<typeof sources.resolveWorkActor>>) => void;
    vi.spyOn(sources, 'resolveWorkActor').mockReturnValue(new Promise(done => { answer = done; }));
    const form = await openPeople();
    fireEvent.change(within(form).getByLabelText('Person', field), { target: { value: 'TEST-new' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Find person' }));
    fireEvent.click(within(form).getByRole('button', { name: 'Cancel' }));
    fireEvent.click(within(await step(2)).getByRole('button', { name: 'Add a person to TEST-source name' }));
    await act(async () => answer({ status: 'FOUND', actors: [{ providerUserId: '900456', handle: 'TEST-new', displayName: 'TEST-new' }], detail: null }));
    expect(screen.getByRole('button', { name: 'Allow person' })).toBeDisabled();
  });
});

describe('ceiling and labels', () => {
  it('saves a ceiling with its version, keeping the label mappings and the revision', async () => {
    const newer = { ...profile, version: 4 };
    vi.mocked(policies.profiles).mockResolvedValue([profile, newer]);
    renderFactory();
    fireEvent.click(within(await step(3)).getByRole('button', { name: 'Change' }));
    fireEvent.change(screen.getByLabelText('Repository ceiling', field), { target: { value: 'TEST-profile:4' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save ceiling' }));
    await waitFor(() => expect(policies.savePolicy).toHaveBeenCalledWith(repository.id, { revision: 7, ceiling: { id: profile.id, version: 4 }, mappings: { 'TEST-work': { id: profile.id, version: 3 } } }));
  });
  it('cannot edit labels before a ceiling exists, and says why', async () => {
    vi.mocked(policies.policy).mockResolvedValue(policy({ ceiling: null, mappings: {} }));
    renderFactory();
    const labels = within(await step(4));
    expect(labels.getByRole('button', { name: 'Edit labels' })).toBeDisabled();
    expect(labels.getByText(/Choose the ceiling in step 3 first/)).toBeInTheDocument();
  });
  async function editLabels() {
    renderFactory();
    fireEvent.click(within(await step(4)).getByRole('button', { name: 'Edit labels' }));
    return screen.getByRole('group', { name: 'Edit label mappings' });
  }
  it('saves when an added mapping row is left blank', async () => {
    const form = await editLabels();
    fireEvent.click(within(form).getByRole('button', { name: 'Add label mapping' }));
    fireEvent.click(within(form).getByRole('button', { name: 'Save labels' }));
    await waitFor(() => expect(policies.savePolicy).toHaveBeenCalledWith(repository.id, { revision: 7, ceiling: { id: profile.id, version: 3 }, mappings: { 'TEST-work': { id: profile.id, version: 3 } } }));
  });
  it('refuses a half-filled row and a duplicate label by name', async () => {
    const form = await editLabels();
    fireEvent.click(within(form).getByRole('button', { name: 'Add label mapping' }));
    fireEvent.change(within(form).getByLabelText('Label 2', field), { target: { value: 'TEST-half' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save labels' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Label 2 needs both a ticket label and a profile version.');
    fireEvent.change(within(form).getByLabelText('Label 2', field), { target: { value: ' TEST-work ' } });
    fireEvent.change(within(form).getByLabelText('Label profile 2', field), { target: { value: 'TEST-profile:3' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save labels' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Each label needs one mapping.');
    expect(policies.savePolicy).not.toHaveBeenCalled();
  });
  it('keeps a failed save and what was typed visible', async () => {
    vi.mocked(policies.savePolicy).mockRejectedValue(new Error('TEST-policy changed; reload'));
    const form = await editLabels();
    fireEvent.click(within(form).getByRole('button', { name: 'Save labels' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('TEST-policy changed; reload');
    expect(within(form).getByLabelText('Label 1', field)).toHaveValue('TEST-work');
  });
});

describe('presets', () => {
  it('shows what it will create, defaults the ceiling to the most careful preset, then applies', async () => {
    vi.mocked(policies.policy).mockResolvedValue(policy({ ceiling: null, mappings: {} }));
    renderFactory();
    fireEvent.click(within(await step(4)).getByRole('button', { name: 'Use presets' }));
    const form = screen.getByRole('group', { name: 'Use presets' });
    expect(within(form).getAllByText(/^new profile, precedence/)).toHaveLength(3);
    expect(within(form).getByLabelText('Preset ceiling', field)).toHaveValue('suggest');
    fireEvent.click(within(form).getByRole('button', { name: 'Apply presets' }));
    await waitFor(() => expect(policies.savePolicy).toHaveBeenCalledTimes(1));
    expect(policies.saveProfile).toHaveBeenCalledTimes(3);
    const saved = vi.mocked(policies.savePolicy).mock.calls[0][1];
    expect(Object.keys(saved.mappings)).toEqual(['spire:suggest', 'spire:assisted', 'spire:auto']);
    expect(saved.ceiling).toEqual(saved.mappings['spire:suggest']);
  });
  it('re-reads profiles after a failed apply so a retry reuses what was created', async () => {
    vi.mocked(policies.policy).mockResolvedValue(policy({ ceiling: null, mappings: {} }));
    vi.mocked(policies.savePolicy).mockRejectedValueOnce(new Error('TEST-policy save failed'));
    renderFactory();
    fireEvent.click(within(await step(4)).getByRole('button', { name: 'Use presets' }));
    fireEvent.click(screen.getByRole('button', { name: 'Apply presets' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('TEST-policy save failed');
    await waitFor(() => expect(policies.profiles).toHaveBeenCalledTimes(2));
  });
});
