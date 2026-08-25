import { GLOBAL_SCOPE, type PromptInheritance } from '../api';

// Display labels per prompt kind (see spire-contract PromptKind) — shared by the prompts list
// and detail pages. Falls back to the raw slug for any kind without friendly copy yet.
export const KIND_LABELS: Record<string, string> = {
  review: 'Review',
  reconcile: 'Reconcile',
  followup: 'Follow-up',
};

/**
 * The provenance line for the prompt detail page: which row actually supplied the text being
 * shown. `inheritedFrom` alone is ambiguous at global scope -- "global" there means "this is the
 * override you're looking at", not "inherited from elsewhere" -- so the label is scope-aware.
 */
export function provenanceLabel(scope: string, inheritedFrom: PromptInheritance): string {
  if (inheritedFrom === 'repo') return 'Overridden for this repository';
  if (inheritedFrom === 'global') {
    return scope === GLOBAL_SCOPE ? 'Applies to every repository' : 'Inherited from global';
  }
  return 'Built-in default';
}

/** The short provenance tag for a prompts-list row. Same ambiguity, a terser shape. */
export function provenanceTag(scope: string, inheritedFrom: PromptInheritance): { text: string; className: string } {
  if (inheritedFrom === 'repo') return { text: 'Custom · this repo', className: 'is-custom' };
  if (inheritedFrom === 'global') {
    return scope === GLOBAL_SCOPE
      ? { text: 'Custom', className: 'is-custom' }
      : { text: 'Inherited · global', className: 'is-inherited' };
  }
  return { text: 'Default', className: '' };
}
