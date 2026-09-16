import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { LlmModelView, WorkItemDetail } from '../../api';
import * as gateway from '../../api';
import * as auth from '../../auth';
import * as api from './workPreparationApi';
import * as defaultsApi from '../repositories/factory/buildDefaultsApi';
import WorkItemPreparation from './WorkItemPreparation';

afterEach(cleanup);
const item = { id: 'TEST-prepared-item', revision: 7, workflowStatus: 'awaiting_input' } as WorkItemDetail;
const reference = (key: string): api.ArtifactReference => ({ title: `TEST-artifact-${key}`, artifact: { sha256: key.repeat(64).slice(0, 64),
  location: { ref: { type: 'GITHUB', origin: 'https://TEST.example', projectId: 'TEST-project', issueId: `TEST-${key}` }, issueKey: key, link: `https://TEST.example/issues/${key}` } } });
const model = (name: string, rates: LlmModelView['rates'] = { INPUT: 100, OUTPUT: 200 }): LlmModelView => ({ id: `TEST-model-${name}`, type: 'openai', name, label: name,
  pricingMode: 'METERED', rates, outputTokenParam: 'MAX_TOKENS', supportsTemperature: true, reasoningEffort: null, extraParams: {}, enabled: true, createdAt: '2026-09-15T00:00:00Z', notBilled: [] });
function show(changed = vi.fn(), roles = ['spire-admin'], value = item) {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-operator', roles });
  // A test that cares about the offered choices mocks them first; this is only the default pair.
  if (!vi.isMockFunction(api.preparationOptions)) vi.spyOn(api, 'preparationOptions').mockResolvedValue({ harnesses: ['TEST-harness'], reportedTypes: { 'TEST-harness': ['INPUT','OUTPUT'] } });
  if (!vi.isMockFunction(gateway.fetchLlmModels)) vi.spyOn(gateway, 'fetchLlmModels').mockResolvedValue([model('TEST-model')]);
  return { changed, ...render(<WorkItemPreparation item={value} changed={changed} />) };
}
async function fill() {
  await screen.findByLabelText('Specification ticket');
  // The selects only hold what the deployment offers, so wait for those answers before choosing.
  await screen.findByRole('option', { name: 'TEST-harness' });
  for (const [label, value] of [['Specification ticket', '71'], ['Plan ticket', '72'], ['Base branch', 'main'], ['Base commit', 'a'.repeat(40)], ['Harness', 'TEST-harness'], ['Model', 'TEST-model']])
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
}
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
it('reads the specification digest before a plan ticket exists', async () => {
  vi.spyOn(api, 'resolveArtifact').mockImplementation(async (_id, key) => reference(key));show();
  fireEvent.change(await screen.findByLabelText('Specification ticket'), { target: { value: '71' } });
  fireEvent.click(screen.getByRole('button', { name: 'Read specification version' }));
  expect(await screen.findByText(reference('71').artifact.sha256)).toBeInTheDocument();
  expect(api.resolveArtifact).toHaveBeenCalledExactlyOnceWith(item.id, '71');
  expect(screen.queryByRole('button', { name: 'Register these versions' })).toBeNull();
  expect(screen.getByText(/"specificationSha256":/)).toHaveTextContent(reference('71').artifact.sha256);
});
it('registers the checked artifact versions with the displayed item revision', async () => {
  vi.spyOn(api, 'resolveArtifact').mockImplementation(async (_id, key) => reference(key));
  vi.spyOn(api, 'registerPreparation').mockResolvedValue({ reason: 'approval_required' });const { changed } = show();await fill();
  fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Register these versions' }));
  await waitFor(() => expect(changed).toHaveBeenCalledOnce());
  expect(api.registerPreparation).toHaveBeenCalledWith(item.id, { expectedRevision: 7, specification: reference('71').artifact, plan: reference('72').artifact,
    baseBranch: 'main', baseCommit: 'a'.repeat(40), harness: 'TEST-harness', model: 'TEST-model' });
});
it('requires a new reference check after the input changes', async () => {
  vi.spyOn(api, 'resolveArtifact').mockImplementation(async (_id, key) => reference(key));show();await fill();
  fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));await screen.findByRole('button', { name: 'Register these versions' });
  fireEvent.change(screen.getByLabelText('Plan ticket'), { target: { value: '73' } });
  expect(screen.queryByRole('button', { name: 'Register these versions' })).toBeNull();
});
it('ignores an older artifact response after checking a new reference', async () => {
  const old = deferred<api.ArtifactReference>();
  vi.spyOn(api, 'resolveArtifact').mockImplementation((_id, key) => key === '71' ? old.promise : Promise.resolve(reference(key)));
  show();await fill();fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));
  fireEvent.change(screen.getByLabelText('Specification ticket'), { target: { value: '73' } });
  fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));await screen.findByText('Specification: TEST-artifact-73');
  await act(async () => old.resolve(reference('71')));
  expect(screen.getByText('Specification: TEST-artifact-73')).toBeInTheDocument();expect(screen.queryByText('Specification: TEST-artifact-71')).toBeNull();
});
it('ignores an older artifact error after a successful new check', async () => {
  const old = deferred<api.ArtifactReference>();
  vi.spyOn(api, 'resolveArtifact').mockImplementation((_id, key) => key === '71' ? old.promise : Promise.resolve(reference(key)));
  show();await fill();fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));
  fireEvent.change(screen.getByLabelText('Specification ticket'), { target: { value: '73' } });
  fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));await screen.findByText('Specification: TEST-artifact-73');
  await act(async () => old.reject(new Error('TEST-old error')));expect(screen.queryByRole('alert')).toBeNull();
});
it('shows a failed registration without announcing a continuation', async () => {
  vi.spyOn(api, 'resolveArtifact').mockImplementation(async (_id, key) => reference(key));
  vi.spyOn(api, 'registerPreparation').mockRejectedValue(new Error('TEST-409 artifacts changed'));const { changed } = show();await fill();
  fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));fireEvent.click(await screen.findByRole('button', { name: 'Register these versions' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-409 artifacts changed');expect(changed).not.toHaveBeenCalled();
});
it('does not notify a different screen when a registration finishes after unmount', async () => {
  const pending = deferred<{ reason: string }>();vi.spyOn(api, 'resolveArtifact').mockImplementation(async (_id, key) => reference(key));
  vi.spyOn(api, 'registerPreparation').mockReturnValue(pending.promise);const { changed, unmount } = show();await fill();
  fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));fireEvent.click(await screen.findByRole('button', { name: 'Register these versions' }));
  unmount();await act(async () => pending.resolve({ reason: 'TEST-registered' }));expect(changed).not.toHaveBeenCalled();
});
it('offers no preparation controls to a viewer', async () => { show(vi.fn(), ['spire-viewer']);await act(async () => {});expect(screen.queryByRole('region', { name: 'Prepare work item' })).toBeNull(); });
it('offers no artifact replacement while the item is active', async () => { show(vi.fn(), ['spire-admin'], { ...item, workflowStatus: 'active' });await act(async () => {});expect(screen.queryByRole('region', { name: 'Prepare work item' })).toBeNull(); });

// The server re-reads both tickets on register. A refusal means what was checked is no longer
// proven, so offering "Register these versions" again would resend a pair the server just refused.
it('drops the checked versions after a refused registration', async () => {
  vi.spyOn(api, 'resolveArtifact').mockImplementation(async (_id, key) => reference(key));
  vi.spyOn(api, 'registerPreparation').mockRejectedValue(new Error('TEST-409 artifacts changed'));show();await fill();
  fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Register these versions' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Check the artifact references again before registering.');
  expect(screen.queryByRole('button', { name: 'Register these versions' })).toBeNull();
  expect(screen.getByLabelText('Plan ticket')).toHaveValue('72');
});
it('shows the plan digest beside the specification digest', async () => {
  vi.spyOn(api, 'resolveArtifact').mockImplementation(async (_id, key) => reference(key));show();await fill();
  fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));
  expect(await screen.findByText(reference('72').artifact.sha256)).toBeInTheDocument();
});
it('says which answer is in flight while the registration is recorded', async () => {
  const pending = deferred<{ reason: string }>();
  vi.spyOn(api, 'resolveArtifact').mockImplementation(async (_id, key) => reference(key));
  vi.spyOn(api, 'registerPreparation').mockReturnValue(pending.promise);show();await fill();
  fireEvent.click(screen.getByRole('button', { name: 'Check artifact references' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Register these versions' }));
  expect(await screen.findByRole('button', { name: 'Registering…' })).toBeDisabled();
  expect(screen.getByRole('status')).toHaveTextContent('Registering the checked versions.');
  await act(async () => pending.resolve({ reason: 'TEST-registered' }));
});

// A harness with no agent image and a model with no price are both refused at dispatch, after the
// operator has typed them and waited. The form offers only what this deployment can run.
it('offers the configured harnesses and the priced models instead of free text', async () => {
  vi.spyOn(api, 'preparationOptions').mockResolvedValue({ harnesses: ['TEST-harness', 'TEST-other-harness'], reportedTypes: { 'TEST-harness': ['INPUT','OUTPUT'], 'TEST-other-harness': ['INPUT','OUTPUT'] } });
  vi.spyOn(gateway, 'fetchLlmModels').mockResolvedValue([model('TEST-model'), model('TEST-unpriced', { INPUT: 100 }), { ...model('TEST-disabled'), enabled: false }]);
  show();
  const harness = await screen.findByLabelText('Harness');
  expect(within(harness).getAllByRole('option').map(option => option.textContent)).toEqual(['Select a harness', 'TEST-harness', 'TEST-other-harness']);
  // Before a harness is chosen there is nothing to judge a model against, so nothing is marked.
  expect(within(await screen.findByLabelText('Model')).getAllByRole('option').map(option => option.textContent))
    .toEqual(['Select a model', 'TEST-model', 'TEST-unpriced']);
  fireEvent.change(harness, { target: { value: 'TEST-harness' } });
  const models = within(await screen.findByLabelText('Model')).getAllByRole('option');
  expect(models.map(option => option.textContent)).toEqual(['Select a model', 'TEST-model', 'TEST-unpriced — no price for Output']);
  expect(models[2]).toBeDisabled();
});

it('reads the base commit from the forge for the named branch', async () => {
  vi.spyOn(api, 'branchHead').mockResolvedValue({ branch: 'main', commit: 'c'.repeat(40) });
  vi.spyOn(api, 'resolveArtifact').mockImplementation(async (_id, key) => reference(key));show();await fill();
  fireEvent.change(screen.getByLabelText('Base commit'), { target: { value: '' } });
  fireEvent.click(screen.getByRole('button', { name: 'Use current head' }));
  await waitFor(() => expect(screen.getByLabelText('Base commit')).toHaveValue('c'.repeat(40)));
  expect(api.branchHead).toHaveBeenCalledWith(item.id, 'main');
});

it('keeps the typed commit when the forge cannot answer', async () => {
  vi.spyOn(api, 'branchHead').mockRejectedValue(new Error('TEST-branch head unavailable'));
  vi.spyOn(api, 'resolveArtifact').mockImplementation(async (_id, key) => reference(key));show();await fill();
  fireEvent.click(screen.getByRole('button', { name: 'Use current head' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('TEST-branch head unavailable');
  expect(screen.getByLabelText('Base commit')).toHaveValue('a'.repeat(40));
});
// M3.5 part B: the repository's saved build setup fills the coordinates nobody should retype.
it('fills the branch, harness and model from the repository build setup', async () => {
  vi.spyOn(api, 'preparationOptions').mockResolvedValue({ harnesses: ['TEST-harness'], reportedTypes: { 'TEST-harness': ['INPUT','OUTPUT'] } });
  vi.spyOn(gateway, 'fetchLlmModels').mockResolvedValue([model('TEST-model')]);
  vi.spyOn(defaultsApi, 'buildDefaults').mockResolvedValue({ revision: 3, baseBranch: 'main', harness: 'TEST-harness',
    model: 'TEST-model', updatedBy: 'TEST-operator', updatedAt: '2026-09-16T00:00:00Z' });
  show(vi.fn(), ['spire-admin'], { ...item, repositoryId: 'TEST-repository' } as WorkItemDetail);
  expect(await screen.findByLabelText('Base branch')).toHaveValue('main');
  expect(screen.getByLabelText('Harness')).toHaveValue('TEST-harness');
  expect(screen.getByLabelText('Model')).toHaveValue('TEST-model');
  expect(defaultsApi.buildDefaults).toHaveBeenCalledWith('TEST-repository');
});
// What is already registered is what the gate binds, so a saved setup must not quietly replace it.
// Every coordinate differs from the saved setup, or the assertion could pass on a value that was replaced.
it('keeps a registered preparation rather than replacing it with the repository setup', async () => {
  vi.spyOn(api, 'preparationOptions').mockResolvedValue({ harnesses: ['TEST-harness', 'TEST-other-harness'], reportedTypes: { 'TEST-harness': ['INPUT','OUTPUT'], 'TEST-other-harness': ['INPUT','OUTPUT'] } });
  vi.spyOn(gateway, 'fetchLlmModels').mockResolvedValue([model('TEST-model'), model('TEST-other-model')]);
  const answer = deferred<defaultsApi.BuildDefaults>();
  vi.spyOn(defaultsApi, 'buildDefaults').mockReturnValue(answer.promise);
  const registered = { ...item, repositoryId: 'TEST-repository', preparation: { specification: reference('71').artifact, plan: reference('72').artifact,
    baseBranch: 'release-1', baseCommit: 'c'.repeat(40), harness: 'TEST-other-harness', model: 'TEST-other-model', registeredBy: 'TEST-operator' } } as WorkItemDetail;
  show(vi.fn(), ['spire-admin'], registered);
  await act(async () => { answer.resolve({ revision: 3, baseBranch: 'main', harness: 'TEST-harness', model: 'TEST-model',
    updatedBy: 'TEST-operator', updatedAt: '2026-09-16T00:00:00Z' }); });
  expect(await screen.findByLabelText('Base branch')).toHaveValue('release-1');
  expect(screen.getByLabelText('Harness')).toHaveValue('TEST-other-harness');
  expect(screen.getByLabelText('Model')).toHaveValue('TEST-other-model');
  // And the setup is not even asked for: there is nothing it could be allowed to change.
  expect(defaultsApi.buildDefaults).not.toHaveBeenCalled();
});

// The saved setup is optional; a slow answer for it must not hold back what this deployment can run.
it('offers the harness and model choices before the repository setup answers', async () => {
  vi.spyOn(api, 'preparationOptions').mockResolvedValue({ harnesses: ['TEST-harness'], reportedTypes: { 'TEST-harness': ['INPUT','OUTPUT'] } });
  vi.spyOn(gateway, 'fetchLlmModels').mockResolvedValue([model('TEST-model')]);
  const answer = deferred<defaultsApi.BuildDefaults>();
  vi.spyOn(defaultsApi, 'buildDefaults').mockReturnValue(answer.promise);
  show(vi.fn(), ['spire-admin'], { ...item, repositoryId: 'TEST-repository' } as WorkItemDetail);
  expect(await screen.findByRole('option', { name: 'TEST-harness' })).toBeInTheDocument();
  expect(await screen.findByRole('option', { name: 'TEST-model' })).toBeInTheDocument();
  await act(async () => { answer.resolve({ revision: 1, baseBranch: 'main', harness: 'TEST-harness', model: 'TEST-model',
    updatedBy: 'TEST-operator', updatedAt: '2026-09-16T00:00:00Z' }); });
});

// A field the operator emptied on purpose is still a field they touched.
it('does not refill a coordinate the operator deliberately cleared', async () => {
  vi.spyOn(api, 'preparationOptions').mockResolvedValue({ harnesses: ['TEST-harness'], reportedTypes: { 'TEST-harness': ['INPUT','OUTPUT'] } });
  vi.spyOn(gateway, 'fetchLlmModels').mockResolvedValue([model('TEST-model')]);
  const answer = deferred<defaultsApi.BuildDefaults>();
  vi.spyOn(defaultsApi, 'buildDefaults').mockReturnValue(answer.promise);
  show(vi.fn(), ['spire-admin'], { ...item, repositoryId: 'TEST-repository' } as WorkItemDetail);
  fireEvent.change(await screen.findByLabelText('Base branch'), { target: { value: 'release-9' } });
  fireEvent.change(screen.getByLabelText('Base branch'), { target: { value: '' } });
  await act(async () => { answer.resolve({ revision: 1, baseBranch: 'main', harness: 'TEST-harness', model: 'TEST-model',
    updatedBy: 'TEST-operator', updatedAt: '2026-09-16T00:00:00Z' }); });
  expect(screen.getByLabelText('Base branch')).toHaveValue('');
  // The untouched ones still take the saved setup.
  expect(screen.getByLabelText('Harness')).toHaveValue('TEST-harness');
});
