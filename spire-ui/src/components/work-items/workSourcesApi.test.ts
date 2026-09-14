import { beforeEach, expect, it, vi } from 'vitest';
import { apiFetch } from '../../auth';
import * as api from './workSourcesApi';
vi.mock('../../auth', () => ({ apiFetch: vi.fn() }));
const source = { id: 'TEST-source', version: { source: 7 } } as api.WorkSource;
beforeEach(() => vi.mocked(apiFetch).mockReset().mockResolvedValue(new Response('{}', { status: 200 })));
it('binds an edit to the source revision', async () => {
  await api.editWorkSource(source, { name: 'TEST-name', accountId: 'TEST-account', enabled: true });
  expect(apiFetch).toHaveBeenCalledWith('/api/work-sources/TEST-source', expect.objectContaining({ method: 'PUT',
    body: JSON.stringify({ name: 'TEST-name', accountId: 'TEST-account', enabled: true, revision: 7 }) }));
});
it('saves the selected identity with its query and source revision', async () => {
  await api.saveWorkActor(source, 'TEST-person', 'TEST-actor');
  expect(apiFetch).toHaveBeenCalledWith('/api/work-sources/TEST-source/actors', expect.objectContaining({ method: 'POST',
    body: JSON.stringify({ handle: 'TEST-person', providerUserId: 'TEST-actor', revision: 7 }) }));
});
it('encodes an actor id and preserves the removal revision', async () => {
  await api.removeWorkActor(source, 'TEST-id/with?path');
  expect(apiFetch).toHaveBeenCalledWith('/api/work-sources/TEST-source/actors/TEST-id%2Fwith%3Fpath?revision=7', { method: 'DELETE' });
});
it.each([202, 204])('accepts an empty %s rescan response', async status => {
  vi.mocked(apiFetch).mockResolvedValue(new Response(null, { status }));
  await expect(api.rescanWorkSource(source.id)).resolves.toBeUndefined();
  expect(apiFetch).toHaveBeenCalledWith('/api/work-sources/TEST-source/rescan', { method: 'POST' });
});
it('surfaces a refused request instead of treating it as a source', async () => {
  vi.mocked(apiFetch).mockResolvedValue(new Response(JSON.stringify('TEST-refused'), { status: 403 }));
  await expect(api.fetchWorkSources()).rejects.toThrow('TEST-refused');
});
it('resolves people at the selected source endpoint', async () => {
  await api.resolveWorkActor(source.id, 'TEST-person');
  expect(apiFetch).toHaveBeenCalledWith('/api/work-sources/TEST-source/actors/resolve', expect.objectContaining({ method: 'POST', body: '{"handle":"TEST-person"}' }));
});
it('loads capabilities for the selected source', async () => {
  await api.workCapabilities(source.id);
  expect(apiFetch).toHaveBeenCalledWith('/api/work-sources/TEST-source/capabilities', { method: 'GET' });
});
