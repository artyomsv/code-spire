import { apiFetch } from '../../auth';
import { workRefusal } from './workReasons';

/** The ticket comment that carries a written artifact: the outbox row's state, and what the tracker returned for it. */
export interface DraftComment { state: string; ref: string | null }
/** A specification or plan the factory wrote (M4 slice 3), decrypted for an operator who may read the item. */
export interface WrittenArtifact { text: string; sha256: string; comment: DraftComment | null }
/** One step of a plan the factory wrote: what to change, and how anyone can tell it is done. */
export interface PlanStep { id: string; title: string; instruction: string; doneWhen: string }
export interface WrittenPlan extends WrittenArtifact { steps: PlanStep[] }
export interface WorkDraftView {
  specification: WrittenArtifact | null;
  plan: WrittenPlan | null;
  revisions: { specification: number; plan: number };
  /** The ticket, linked while the comment has no link of its own. */
  ticketLink: string;
}

export async function workDraft(id: string): Promise<WorkDraftView> {
  const response = await apiFetch(`/api/work-items/${encodeURIComponent(id)}/draft`);
  if (!response.ok) {
    const body = await response.text();
    let refusal: { reason?: string } = {};
    try { refusal = JSON.parse(body) as typeof refusal; } catch { /* not a refusal the API shaped */ }
    throw new Error(refusal.reason ? workRefusal(refusal.reason, null) : `${response.status}: ${body}`);
  }
  return response.json();
}
