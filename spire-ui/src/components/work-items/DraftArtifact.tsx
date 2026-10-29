import { useQuery } from '@tanstack/react-query';
import type { WorkItemDetail } from '../../api';
import PlanView from './PlanView';
import SpecificationView from './SpecificationView';
import { workDraft, type WorkDraftView } from './workDraftApi';
import { workKeys } from './workQueries';

interface DraftItem { id: string; generation: number; events: WorkItemDetail['events'] }

/** The generation and sequence of this generation's newest written artifact; null before the first. */
function draftVersion(item: DraftItem) {
  const writes = item.events.filter(event => event.type === 'DRAFT_ARTIFACT_WRITTEN' && event.generation === item.generation);
  return writes.length === 0 ? null : `${item.generation}:${Math.max(...writes.map(event => event.sequence))}`;
}

function DraftView({ view, part }: { view: WorkDraftView; part: 'spec' | 'plan' }) {
  if (part === 'plan') return view.plan ? <PlanView plan={view.plan} /> : null;
  return view.specification ? <SpecificationView specification={view.specification} ticketLink={view.ticketLink} /> : null;
}

/**
 * The specification or plan the factory wrote for a generated item (M4 slice 3, §6.10). Read only once this generation
 * wrote one, so an item that never had a draft asks the server nothing. A registered draft stays readable here.
 *
 * Keyed on the generation's newest write (review of PR 287, M-2): keyed on whether any was written, a revised
 * specification kept the first text on screen and a plan written after the specification never appeared. A read is
 * shown only under the write it was made for, so a stale one never stands in, and the specification and plan steps
 * share one read. The item's change notice reads it again, since the ticket comment's state changes on its own.
 */
export default function DraftArtifact({ item, part }: { item: DraftItem; part: 'spec' | 'plan' }) {
  const version = draftVersion(item);
  const draft = useQuery({ queryKey: workKeys.draft(item.id, version ?? ''), queryFn: () => workDraft(item.id), enabled: version !== null });
  if (version === null || !draft.data) return null;
  return <DraftView view={draft.data} part={part} />;
}
