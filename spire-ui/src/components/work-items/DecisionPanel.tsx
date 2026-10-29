import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getWorkItem, type WorkItemDetail } from '../../api';
import VerifyResult, { isVerifyResultGate } from './VerifyResult';
import { canAdminister } from '../../auth';
import { useMe } from '../../hooks/useMe';
import SidePanel from '../SidePanel';
import SettingField from '../SettingField';
import DecisionEvidence from './DecisionEvidence';
import * as approvalsApi from './approvalsApi';
import { preparationEvidence, type PreparationEvidence } from './workPreparationApi';
import SpecificationView from './SpecificationView';
import PlanView from './PlanView';
import { workDraft } from './workDraftApi';
import { draftVersion, workKeys } from './workQueries';

/** Revisions a person may ask for per phase (§6.5); then only Approve or Reject remain. */
const MAX_REVISIONS = 3;
const DRAFT_NAMES: Record<string, string> = { spec: 'specification', plan: 'plan' };

/** How many revisions are used, and Revise while any are left. The note it sends is the decision note. */
function ReviseLine({ used }: { used: number }) {
  if (used >= MAX_REVISIONS) return <p className="prov-note">All {MAX_REVISIONS} revisions are used. Approve or reject this decision.</p>;
  return <p className="prov-note"><span>{used} of {MAX_REVISIONS} revisions used</span>. Revise sends the decision note to a new run.</p>;
}

const METHOD_WORDS: Record<string, string> = { merge: 'a merge commit', squash: 'a squash merge', rebase: 'a rebase merge' };

/** What the ticket is told after the merge, under the repository's ticket rule. */
function TicketRuleSentence({ rule }: { rule: string }) {
  if (rule === 'close') return <p>Then it comments on the ticket that the work is merged, and closes the ticket where the tracker allows.</p>;
  return <p>Then it comments on the ticket that the work is merged; the ticket keeps its status.</p>;
}

/** What approving a land decision will do (spec §7.10): the method, the ticket rule, the branch, and the checks when known. */
function LandPreview({ item }: { item: WorkItemDetail }) {
  const land = item.land, pullRequest = item.progress?.execution?.pullRequest;
  const target = pullRequest ? `pull request #${pullRequest.number}` : 'the pull request';
  if (!land) return <section className="decision-sec" aria-label="What land will do">
    <h4>What land will do</h4>
    <p className="prov-note">The land setup was not reported, so the method and the ticket rule are not shown. Approving merges with the repository's saved land setup.</p>
  </section>;
  return <section className="decision-sec" aria-label="What land will do">
    <h4>What land will do</h4>
    <p>Merges {target} with {METHOD_WORDS[land.method] ?? land.method}, then deletes its branch.</p>
    <TicketRuleSentence rule={land.ticketRule} />
    {land.checks && <p>Checks when last read: {land.checks.done} of {land.checks.total} checks done
      {land.checks.failed.length > 0 && <> · not passed: {land.checks.failed.map(check => check.name).join(', ')}</>}</p>}
  </section>;
}

interface Props {
  itemId: string;
  /** The ticket title the list already read, so the panel does not read the tracker a second time. */
  title: string | null;
  onClose: () => void;
  onDecided: (notice: string) => void;
}

interface Loaded { approval: approvalsApi.Approval | null; item: WorkItemDetail }

/**
 * One open decision, beside the list it came from. It shows what the gate binds — the specification,
 * the step, the tree it starts from, the agent and the limits — before it offers Approve, because an
 * approval is a promise to spend within those limits and the old card showed none of it.
 */
/** What the two answer buttons say, and what is reported after each: a verify result gate asks about a result, not permission. */
interface AnswerWords { yes: string; yesBusy: string; no: string; noBusy: string; done: (approve: boolean, phase: string) => string }
const RESULT_WORDS: AnswerWords = {
  yes: 'Retry build', yesBusy: 'Retrying…', no: 'Stop', noBusy: 'Stopping…',
  done: (approve) => (approve ? 'Retrying the build.' : 'Stopped after verification.'),
};
const PERMISSION_WORDS: AnswerWords = {
  yes: 'Approve', yesBusy: 'Approving…', no: 'Reject', noBusy: 'Rejecting…',
  done: (approve, phase) => (approve ? `Approved the ${phase} decision. The item continues.` : `Rejected the ${phase} decision. The item stops here.`),
};

function headingFor(resultGate: boolean, phase: string | undefined): string {
  if (resultGate) return 'Verification did not pass';
  return phase ? `Approve the ${phase}` : 'Decision';
}

export default function DecisionPanel({ itemId, title, onClose, onDecided }: Props) {
  const { me } = useMe();
  const admin = canAdminister(me);
  const [answerError, setAnswerError] = useState('');
  const [note, setNote] = useState(''), [answering, setAnswering] = useState<boolean | 'revise' | null>(null);
  // Reuse an answer identity after a transport failure, but never attach it to a different gate, answer or note.
  const attempt = useRef<{ key: string; gate: string; approve: boolean; note: string } | null>(null);
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);

  // The open decisions and the item are read apart, and the item under the key the detail page keeps it under. On the
  // list the change feed re-reads both while the panel is open; Approve follows the binding checks below, so a newer
  // read can only take Approve away, never offer it for something the operator did not see.
  const approval = useQuery({ queryKey: workKeys.approvals(false), queryFn: () => approvalsApi.approvals(false),
    select: open => open.find(row => row.workItemId === itemId) ?? null });
  const itemRead = useQuery({ queryKey: workKeys.item(itemId), queryFn: () => getWorkItem(itemId) });
  // Loaded once both answered: "no open decision" answers null, and a failed re-read keeps the last answer.
  const loaded: Loaded | null = approval.data !== undefined && itemRead.data !== undefined ? { approval: approval.data, item: itemRead.data } : null;
  const readFailure = approval.error ?? itemRead.error;
  const error = answerError || (readFailure ? String(readFailure) : '');

  // The ticket texts are an admin read, loaded beside the decision rather than before it: the session
  // answers after the panel opens, and waiting for it must not blank a decision already on screen.
  const gateKey = loaded?.approval ? `${loaded.approval.gate.id}:${loaded.approval.gate.version}` : null;
  const preparation = loaded?.item.preparation ?? null;
  const prepared = preparation ? `${preparation.specification.sha256}:${preparation.plan.sha256}` : null;
  const evidenceWanted = admin && !!gateKey && !!prepared;
  const evidenceRead = useQuery({ queryKey: workKeys.evidence(itemId, `${gateKey}:${prepared}`), queryFn: () => preparationEvidence(itemId),
    enabled: evidenceWanted });
  const evidence: { value: PreparationEvidence | null; error: string } = evidenceWanted
    ? { value: evidenceRead.data ?? null, error: evidenceRead.error ? String(evidenceRead.error) : '' } : { value: null, error: '' };

  // A generated item's spec or plan gate binds what the factory wrote, not a prepared task (M4 slice 3, A6).
  const gatePhase = loaded?.approval?.gate.phase;
  const draftPhase = !preparation && (gatePhase === 'spec' || gatePhase === 'plan') && !!loaded?.approval?.gate.artifact;
  const draftWanted = admin && !!gateKey && draftPhase;
  // Keyed as the detail page keys its draft, so both share one read; 'current' when the events show no write yet.
  const draftRead = useQuery({ queryKey: workKeys.draft(itemId, (loaded ? draftVersion(loaded.item) : null) ?? 'current'),
    queryFn: () => workDraft(itemId), enabled: draftWanted });
  const draft = { loaded: draftWanted && (draftRead.isSuccess || draftRead.isError), value: draftWanted ? draftRead.data ?? null : null };

  async function decide(approve: boolean) {
    const gate = loaded?.approval?.gate;
    if (!gate) return;
    const previous = attempt.current, bound = `${gate.id}:${gate.version}`;
    const decision = previous && previous.gate === bound && previous.approve === approve && previous.note === note
      ? previous : { key: crypto.randomUUID(), gate: bound, approve, note };
    attempt.current = decision; setAnswering(approve); setAnswerError('');
    try {
      await approvalsApi.answer(gate, decision.key, approve, note);
      if (live.current) onDecided(words.done(approve, gate.phase));
    } catch (failure) { if (live.current) setAnswerError(String(failure)); }
    finally { if (live.current) setAnswering(null); }
  }

  // Revise is a rejection that keeps the item going (decision 8): the note is what the next run is given.
  async function reviseDraft() {
    const gate = loaded?.approval?.gate;
    if (!gate) return;
    const previous = attempt.current, bound = `${gate.id}:${gate.version}:revise`;
    const decision = previous && previous.gate === bound && previous.note === note ? previous : { key: crypto.randomUUID(), gate: bound, approve: false, note };
    attempt.current = decision; setAnswering('revise'); setAnswerError('');
    try {
      await approvalsApi.revise(gate, decision.key, note);
      if (live.current) onDecided(`Asked for a new ${DRAFT_NAMES[gate.phase] ?? gate.phase}. The factory writes it with your note.`);
    } catch (failure) { if (live.current) setAnswerError(String(failure)); }
    finally { if (live.current) setAnswering(null); }
  }

  const gate = loaded?.approval?.gate;
  // A verify result gate asks what to do about a result, so its answers are named for what they do (M4).
  const resultGate = !!loaded && isVerifyResultGate(loaded.item);
  const words = resultGate ? RESULT_WORDS : PERMISSION_WORDS;
  const answeringNow = answering !== null;
  // Approving a plan is a promise to build what the panel shows, so Approve waits until the texts on
  // screen were read against the very binding the decision stores. A land decision binds a commit,
  // not these texts, so it does not wait for them. Rejecting needs no evidence and stays available.
  const needsTexts = !!preparation && !!gate && gate.phase !== 'land';
  // The decision and the item are read separately. A decision that binds a preparation the item no
  // longer has cannot be matched to any texts, so it is not approvable either.
  const written = !draftPhase ? null : gatePhase === 'plan' ? draft.value?.plan ?? null : draft.value?.specification ?? null;
  const draftName = DRAFT_NAMES[gatePhase ?? ''] ?? 'specification';
  const draftBound = !!written && written.sha256 === gate?.artifact;
  const draftChanged = !!written && !draftBound;
  const orphaned = !preparation && !!gate && gate.phase !== 'land' && gate.artifact !== null && (!draftPhase || draft.loaded && !written);
  const readable = !!evidence.value && !evidence.value.reason;
  const reported = evidence.value?.binding;
  const unreported = needsTexts && readable && reported === undefined;
  const mismatched = needsTexts && readable && reported !== undefined && reported !== gate?.artifact;
  const bound = !orphaned && (!draftPhase || draftBound) && (!needsTexts || readable && reported !== undefined && reported === gate?.artifact);
  const revisionsUsed = gatePhase === 'plan' ? draft.value?.revisions.plan ?? 0 : draft.value?.revisions.specification ?? 0;
  const revisable = admin && draftBound && revisionsUsed < MAX_REVISIONS;
  const heading = headingFor(resultGate, gate?.phase);
  const verification = loaded?.item.progress?.execution?.verification;
  const subtitle = !loaded ? undefined : title ? `${title} · ${loaded.item.issueKey} · ${loaded.item.repository}` : `${loaded.item.issueKey} · ${loaded.item.repository}`;
  return <SidePanel title={heading} subtitle={subtitle} busy={answering !== null} onClose={onClose} wide
    actions={<>
      {/* The footer sits outside the panel's fieldset, so the lock is repeated here. */}
      {admin && gate && <button className="btn" type="button" disabled={answeringNow || !bound} onClick={() => void decide(true)}>{answering === true ? words.yesBusy : words.yes}</button>}
      {revisable && <button className="btn-ghost" type="button" disabled={answeringNow || !note.trim()} onClick={() => void reviseDraft()}>{answering === 'revise' ? 'Revising…' : 'Revise with a note'}</button>}
      {admin && gate && <button className="btn-ghost danger" type="button" disabled={answeringNow} onClick={() => void decide(false)}>{answering === false ? words.noBusy : words.no}</button>}
      <button className="btn-ghost" type="button" disabled={answeringNow} onClick={onClose}>{gate ? 'Cancel' : 'Close'}</button>
    </>}>
    {!loaded && !error && <p className="prov-note" role="status" aria-busy="true">Loading the decision…</p>}
    {loaded && !gate && <p className="prov-note">This item has no open decision. It may have been answered, expired or replaced.</p>}
    {loaded && gate && <>
      {resultGate && verification && <section className="decision-sec" aria-label="What the checks found">
        <h4>What the checks found</h4><VerifyResult verification={verification} /></section>}
      {draftBound && written && <section className="decision-sec" aria-label="What you approve">
        <h4>What you approve</h4>
        {gatePhase === 'plan' && draft.value?.plan ? <PlanView plan={draft.value.plan} />
          : <SpecificationView specification={written} ticketLink={draft.value?.ticketLink ?? loaded.item.trackerUrl} />}
        {admin && <ReviseLine used={revisionsUsed} />}</section>}
      {draftChanged && <p className="prov-error" role="alert">The {draftName} changed after this decision was opened, so it is not what this decision binds. Approve is not offered; answering only closes this decision.</p>}
      <DecisionEvidence item={loaded.item} approval={loaded.approval!} evidence={mismatched || unreported ? null : evidence.value} evidenceError={evidence.error} />
      {gate.phase === 'land' && <LandPreview item={loaded.item} />}
      {mismatched && <p className="prov-error" role="alert">The prepared task changed after this decision was opened, so these tickets are not what it binds. Approve is not offered; answering only closes this decision. Register the current versions to get a new one.</p>}
      {orphaned && <p className="prov-error" role="alert">This decision binds a prepared task the item no longer has. Approve is not offered; answering only closes this decision.</p>}
      {unreported && <p className="prov-error" role="alert">The server does not say which prepared version these tickets belong to, so Approve is not offered. Update the orchestrator to the version this dashboard expects.</p>}
      {admin && needsTexts && !evidence.value && !evidence.error && <p className="prov-note" role="status">Reading the tickets. Approve is offered once they are on screen.</p>}
      {admin ? <SettingField label="Decision note" scope="approval" hint="Optional. Recorded with the decision and shown in the item's history.">
        <textarea aria-label="Decision note" value={note} onChange={event => setNote(event.target.value)} placeholder="Why you approve or reject" /></SettingField>
        : <p className="prov-note">Only an administrator can answer this decision.</p>}
      {answering !== null && <p className="prov-note" role="status">Recording your decision. The panel unlocks when the server answers.</p>}
    </>}
    {error && <p className="prov-error" role="alert">{error}</p>}
  </SidePanel>;
}
