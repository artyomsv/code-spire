import { beforeEach, expect, it, vi } from 'vitest';
import * as auth from '../../auth';
import { resumeWorkItem, getWorkItems, type WorkItemSummary } from '../../api';
beforeEach(() => { vi.spyOn(auth, 'apiFetch').mockResolvedValue(new Response('{}')); });
it('binds resume to the displayed revision and explicit readmission choice', async () => {
  await resumeWorkItem({ id: 'TEST-item', revision: 11 } as WorkItemSummary, true);
  expect(JSON.parse(String(vi.mocked(auth.apiFetch).mock.calls[0][1]?.body))).toEqual({ expectedRevision: 11, readmit: true });
});
it('passes the workflow filter to the server that calculates page totals', async () => {
  await getWorkItems(50, 25, ['waiting_approval']);
  expect(auth.apiFetch).toHaveBeenCalledWith('/api/work-items?offset=50&limit=25&status=waiting_approval');
});
// "Needs you" is several statuses; the server must receive all of them, not the first.
it('sends every status of a grouped filter in one request', async () => {
  await getWorkItems(0, 50, ['waiting_approval', 'awaiting_input', 'suspended']);
  expect(auth.apiFetch).toHaveBeenCalledWith('/api/work-items?offset=0&limit=50&status=waiting_approval%2Cawaiting_input%2Csuspended');
});
it('sends no status when the filter is every status', async () => {
  await getWorkItems(0, 50, []);
  expect(auth.apiFetch).toHaveBeenCalledWith('/api/work-items?offset=0&limit=50');
});
