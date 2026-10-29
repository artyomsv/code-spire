/**
 * Every work-item read's query key, under one root, so a change notice can name exactly the reads it concerns
 * (docs/superpowers/specs/2026-10-28-live-data-design.md §3.3).
 */
export const workKeys = {
  all: ['work-items'] as const,
  lists: () => ['work-items', 'list'] as const,
  list: (filterId: string, offset: number) => ['work-items', 'list', filterId, offset] as const,
  item: (id: string) => ['work-items', 'item', id] as const,
  /** The ticket text, read from the forge: no database trigger covers it, so no notice re-reads it. */
  tracker: (id: string) => ['work-items', 'tracker', id] as const,
  drafts: (id: string) => ['work-items', 'draft', id] as const,
  draft: (id: string, version: string) => ['work-items', 'draft', id, version] as const,
  steps: (id: string) => ['work-items', 'steps', id] as const,
  /** The open (`false`) or the answered (`true`) decisions. */
  approvals: (answered: boolean) => ['work-items', 'approvals', answered] as const,
  allApprovals: () => ['work-items', 'approvals'] as const,
  /**
   * The ticket texts a decision binds, read from the tracker: no database trigger covers them, so no notice re-reads
   * them. `binding` names the gate and the preparation they were read for, so a new one is a new read.
   */
  evidence: (id: string, binding: string) => ['work-items', 'evidence', id, binding] as const,
};
