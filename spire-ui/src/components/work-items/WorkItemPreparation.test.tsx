import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { WorkItemDetail } from '../../api';
import * as auth from '../../auth';
import * as api from './workPreparationApi';
import WorkItemPreparation from './WorkItemPreparation';

afterEach(cleanup);
const item = { id: 'TEST-prepared-item', revision: 7, workflowStatus: 'awaiting_input' } as WorkItemDetail;
const reference = (key: string): api.ArtifactReference => ({ title: `TEST-artifact-${key}`, artifact: { sha256: key.repeat(64).slice(0, 64),
  location: { ref: { type: 'GITHUB', origin: 'https://TEST.example', projectId: 'TEST-project', issueId: `TEST-${key}` }, issueKey: key, link: `https://TEST.example/issues/${key}` } } });
function show(changed = vi.fn(), roles = ['spire-admin'], value = item) {
  vi.spyOn(auth, 'fetchMe').mockResolvedValue({ authEnabled: true, authenticated: true, user: 'TEST-operator', roles });
  return { changed, ...render(<WorkItemPreparation item={value} changed={changed} />) };
}
async function fill() {
  await screen.findByLabelText('Specification ticket');
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
