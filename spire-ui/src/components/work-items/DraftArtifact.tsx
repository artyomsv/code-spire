import { useEffect, useState } from 'react';
import type { WorkItemDetail } from '../../api';
import PlanView from './PlanView';
import SpecificationView from './SpecificationView';
import { workDraft, type WorkDraftView } from './workDraftApi';

interface DraftItem { id: string; generation: number; events: WorkItemDetail['events'] }

interface DraftRead { version: string; view: WorkDraftView }

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
 * Read again for every artifact the generation writes, keyed on its newest write (review of PR 287, M-2): keyed on
 * whether any was written, a revised specification kept the first text on screen and a plan written after the
 * specification never appeared. A read is shown only for the write it was made for, so a stale one never stands in.
 * The dashboard has no React Query, so the key is the effect's dependency rather than a query key.
 */
export default function DraftArtifact({ item, part }: { item: DraftItem; part: 'spec' | 'plan' }) {
  const version = draftVersion(item);
  const [draft, setDraft] = useState<DraftRead | null>(null);
  useEffect(() => {
    if (version === null) return;
    let live = true;
    workDraft(item.id).then(view => { if (live) setDraft({ version, view }); }).catch(() => { if (live) setDraft(null); });
    return () => { live = false; };
  }, [item.id, version]);
  if (version === null || !draft || draft.version !== version) return null;
  return <DraftView view={draft.view} part={part} />;
}
