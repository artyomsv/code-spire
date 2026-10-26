import type { WorkItemDetail, WorkItemSummary } from '../../api';
import { actorLabel } from '../actorsApi';
import { landReason, workReason } from './workReasons';

/**
 * Where a work item stands, in the terms the list and the detail both draw.
 *
 * The backend reports a phase and a workflow status; a reader needs "which phases are done, where
 * did it stop, what comes next and who decides it". Kept free of React so the rules are tested once
 * and every screen draws the same answer.
 */
export const JOURNEY = ['intake', 'spec', 'plan', 'build', 'verify', 'deliver', 'review', 'land'] as const;
export type JourneyPhase = typeof JOURNEY[number];

/** What a list row carries: the summary, plus the parts of the detail view the list endpoint also returns. */
export type WorkItemRow = WorkItemSummary & Partial<Pick<WorkItemDetail, 'effectiveModes' | 'progress' | 'gate' | 'preparationHealth'>>;

/** How the current phase stands. `ignored` is an item its policy does not run at this phase; `done` is past `land`. */
export type Standing = 'waiting' | 'blocked' | 'running' | 'stopped' | 'failed' | 'ignored' | 'done';

export type Cell = 'done' | `now-${Standing}` | 'later-auto' | 'later-ask' | 'later-off' | 'later-unknown';

export interface Filter {
  id: string;
  label: string;
  /** Empty means every status. */
  statuses: string[];
}

/** The groups a reader triages by. "Needs you" is every status that waits on a person. */
export const FILTERS: Filter[] = [
  { id: 'all', label: 'All', statuses: [] },
  { id: 'needs-you', label: 'Needs you', statuses: ['waiting_approval', 'awaiting_input', 'suspended'] },
  { id: 'running', label: 'Running', statuses: ['active'] },
  { id: 'stopped', label: 'Stopped', statuses: ['stopped', 'failed', 'capability_unavailable'] },
  { id: 'ignored', label: 'Not eligible', statuses: ['not_eligible'] },
  { id: 'finished', label: 'Finished', statuses: ['completed', 'retired'] },
];

export function filterById(id: string | null): Filter {
  return FILTERS.find(filter => filter.id === id) ?? FILTERS[0];
}

export function standing(status: string): Standing {
  switch (status) {
    case 'waiting_approval': return 'waiting';
    case 'awaiting_input': case 'suspended': return 'blocked';
    case 'active': return 'running';
    case 'stopped': case 'capability_unavailable': return 'stopped';
    case 'failed': return 'failed';
    case 'not_eligible': return 'ignored';
    case 'completed': return 'done';
    // A retired item stopped wherever it was when its ticket was deleted or moved; it did not finish.
    case 'retired': return 'stopped';
    // An unknown status stops the strip rather than painting it as progress.
    default: return 'stopped';
  }
}

/** The position of a phase in the journey; `complete` is past the last phase. */
export function phaseIndex(phase: string): number {
  if (phase === 'complete') return JOURNEY.length;
  const index = JOURNEY.indexOf(phase as JourneyPhase);
  return index < 0 ? 0 : index;
}

function later(mode: string | undefined): Cell {
  if (mode === undefined) return 'later-unknown';
  if (mode === 'off') return 'later-off';
  return mode === 'approve' ? 'later-ask' : 'later-auto';
}

/** One cell per phase: done before the current one, the current one with its standing, then who decides the rest. */
export function journeyCells(item: Pick<WorkItemRow, 'phase' | 'workflowStatus' | 'effectiveModes'>): Cell[] {
  const current = phaseIndex(item.phase);
  const state = standing(item.workflowStatus);
  return JOURNEY.map((phase, index): Cell => {
    if (index < current || state === 'done' && item.workflowStatus === 'completed') return 'done';
    if (index === current) return `now-${state}`;
    return later(item.effectiveModes?.[phase.toUpperCase()]);
  });
}

export interface NextAction {
  label: string;
  /** A route inside the dashboard. */
  to: string;
}

// specification_to_write and plan_to_write are not here: the factory writes those drafts itself (M4 slice 3).
const PREPARATION_REASONS = new Set(['specification_required', 'artifacts_changed', 'artifacts_unavailable', 'single_step_plan_required',
  'artifacts_changed_requires_new_decision']);

/**
 * The one thing a person can do next, or null when the item waits on nobody. An open decision opens
 * in the side panel of the list itself, so approving never needs a second page.
 */
export function nextAction(item: WorkItemRow): NextAction | null {
  if (item.workflowStatus === 'waiting_approval' && item.gate?.state === 'OPEN')
    return { label: `Review the ${item.gate.phase} decision`, to: `/work-items?filter=needs-you&decide=${encodeURIComponent(item.id)}` };
  // Unknown usage is a missing price or a missing measurement; the row cannot tell which, so it sends
  // the reader to the item, whose build step links the run and the model prices.
  if (item.workflowStatus === 'awaiting_input' && item.reason === 'run_usage_unknown')
    return { label: 'Check the run cost', to: `/work-items/${encodeURIComponent(item.id)}` };
  if (item.workflowStatus === 'awaiting_input' && PREPARATION_REASONS.has(item.reason))
    return { label: 'Prepare the task', to: `/work-items/${encodeURIComponent(item.id)}` };
  if (item.workflowStatus === 'suspended') return { label: 'Resume', to: `/work-items/${encodeURIComponent(item.id)}` };
  // Every plan step finished empty (M4 slice 3, §6.7): the item offers Stop and Readmit.
  if (item.reason === 'plan_produced_no_change') return { label: 'Stop or readmit', to: `/work-items/${encodeURIComponent(item.id)}` };
  return null;
}

/** What the land step reads from. */
export type LandItem = Pick<WorkItemDetail, 'phase' | 'workflowStatus' | 'reason' | 'gate' | 'land' | 'progress' | 'people'>;

/** What a land fix is fixing, in the words the Land step uses. */
const FIXING: Record<string, string> = {
  land_checks_failed: 'a failed check', land_conflict: 'a conflict with the base branch', land_branch_behind: 'the branch is behind its base',
};

/** The item limit a land fix would have crossed, as the backend names it (WorkProgress.limitReached). */
const LIMITS: Record<string, string> = {
  usage_unknown: 'the usage of an earlier run is unknown', maxRunsPerItem: 'runs per item', maxStepsPerPlan: 'steps per plan',
  maxWallClockSeconds: 'wall-clock time', maxCostMillicents: 'cost', maxCallsPerItem: 'calls per item',
};

/**
 * The land row's note in words: a Jira ticket left open, a branch that would not delete, the limit a fix would have
 * crossed, or what the spent fix attempts were fixing. Null when the row has none.
 */
export function landNote(note: string | null | undefined): string | null {
  if (!note) return null;
  if (LIMITS[note]) return `Limit reached: ${LIMITS[note]}.`;
  if (FIXING[note]) return `It was fixing ${FIXING[note]}.`;
  return workReason(note);
}

/** What became of the branch after the merge; null while the delete has not finished. */
function branchWords(branchDeleted: boolean | null | undefined): string {
  if (branchDeleted === true) return ', branch deleted';
  if (branchDeleted === false) return ', branch not deleted';
  return ', deleting the branch';
}

function merger(item: LandItem, mergedBy: string): string {
  if (mergedBy === 'factory') return 'the factory';
  const person = item.people?.find(candidate => candidate.providerUserId === mergedBy);
  return person ? actorLabel(person) : `GitHub user ${mergedBy}`;
}

/**
 * What the Land step says (spec §7.10): waiting for approval, checking, waiting for checks, merging, merged by whom,
 * fixing, or needs you with GitHub's reason. Null when land has nothing to say yet.
 */
export function landProgress(item: LandItem): string | null {
  const land = item.land ?? null;
  const landing = item.progress?.execution?.landing;
  if (item.workflowStatus === 'completed' && landing) {
    return `Merged ${landing.mergeCommit.slice(0, 7)} by ${merger(item, landing.mergedBy)}${branchWords(land?.branchDeleted)}`;
  }
  if (land?.fixInProgress) {
    const what = FIXING[land.reason ?? ''] ?? workReason(land.reason ?? '');
    return land.reason === 'land_branch_behind' ? `Fixing: ${what}, not counted as an attempt` : `Fixing: ${what}, attempt ${land.attempt} of ${land.attemptsAllowed}`;
  }
  if (item.phase !== 'land') return null;
  if (item.workflowStatus === 'waiting_approval' && item.gate?.state === 'OPEN' && item.gate.phase === 'land') return 'Waiting for your approval';
  if (land?.handedOver) return `Needs you: ${landReason(item.reason, land.message)}`;
  if (item.workflowStatus !== 'active' || !land) return null;
  switch (land.state) {
    case 'pending': case 'checking': return 'Checking GitHub';
    case 'waiting_checks': return land.checks ? `Waiting for checks (${land.checks.done} of ${land.checks.total} done)` : 'Waiting for checks';
    case 'merging': case 'uncertain': return 'Merging';
    default: return null;
  }
}
