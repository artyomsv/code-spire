// Display labels per prompt kind (see spire-contract PromptKind) — shared by the prompts list
// and detail pages. Falls back to the raw slug for any kind without friendly copy yet.
export const KIND_LABELS: Record<string, string> = {
  review: 'Review',
  reconcile: 'Reconcile',
  followup: 'Follow-up',
};
