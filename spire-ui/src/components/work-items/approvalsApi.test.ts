import { beforeEach, expect, it, vi } from 'vitest';
import * as auth from '../../auth';
import { answer, type Gate } from './approvalsApi';
const gate = { id: 'TEST-gate', version: 11 } as Gate;
beforeEach(() => { vi.spyOn(auth, 'apiFetch').mockResolvedValue(new Response('{}')); });
it('sends the expected version and stable answer key', async () => {
  await answer(gate, 'TEST-key', false, 'TEST-note');
  expect(JSON.parse(String(vi.mocked(auth.apiFetch).mock.calls[0][1]?.body))).toEqual({ expectedVersion: 11, idempotencyKey: 'TEST-key', approve: false, note: 'TEST-note' });
});
it('explains a conflicting or expired answer', async () => {
  vi.mocked(auth.apiFetch).mockResolvedValue(new Response('TEST-conflict', { status: 409 }));
  await expect(answer(gate, 'TEST-key', true, '')).rejects.toThrow('The decision changed or expired. Refresh approvals before deciding again.');
});
it('explains unavailable current policy without claiming an approval', async () => {
  vi.mocked(auth.apiFetch).mockResolvedValue(new Response('TEST-outage', { status: 503 }));
  await expect(answer(gate, 'TEST-key', true, '')).rejects.toThrow('No approval was recorded; retry when the source is available.');
});
