import { apiFetch } from '../../auth';
import { workRefusal } from './workReasons';

export interface Gate {
  id: string; version: number; state: 'OPEN' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'SUPERSEDED';
  phase: string; generation: number; itemRevision: number; policyRevision: number; artifact: string | null;
  openedAt: string; expiresAt: string; resolver: string | null; channel: string | null; note: string | null;
}
export interface Approval { workItemId: string; issueKey: string; gate: Gate; prReviewAvailable?: boolean; prReviewDetail?: string; trackerCommand?: string }
export async function approvals(history = false): Promise<Approval[]> {
  const response = await apiFetch(`/api/approvals?history=${history}`);
  if (!response.ok) throw new Error(`${response.status}: ${await response.text()}`);
  return response.json();
}
export async function answer(gate: Gate, idempotencyKey: string, approve: boolean, note: string): Promise<void> {
  const response = await apiFetch(`/api/approvals/${encodeURIComponent(gate.id)}/answer`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ expectedVersion: gate.version, idempotencyKey, approve, note }) });
  if (!response.ok) {
    const detail = await response.text();
    // A superseded plan decision names the ticket that moved, which is what the approver re-reads.
    let refusal: { reason?: string; detail?: string } = {};
    try { refusal = JSON.parse(detail) as typeof refusal; } catch { /* not a refusal this API shaped */ }
    if (response.status === 409 && refusal.detail) throw new Error(`The decision changed. ${workRefusal(refusal.reason ?? '', refusal.detail)}`);
    if (response.status === 409) throw new Error(`The decision changed or expired. Refresh approvals before deciding again. ${detail}`);
    if (response.status === 503) throw new Error(`Current policy could not be confirmed. No approval was recorded; retry when the source is available. ${detail}`);
    throw new Error(`${response.status}: ${detail}`);
  }
}
