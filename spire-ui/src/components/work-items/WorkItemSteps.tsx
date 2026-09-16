import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { WorkItemDetail } from '../../api';
import { actorLabel } from '../actorsApi';
import { JOURNEY, journeyCells, type Cell, type JourneyPhase } from './workJourney';
import { workReason } from './workReasons';

type Item = Pick<WorkItemDetail, 'id' | 'generation' | 'phase' | 'workflowStatus' | 'reason' | 'effectiveModes' | 'profile' | 'appliedLabels' | 'ignoredLabels'
  | 'people' | 'preparation' | 'builds' | 'gate' | 'events' | 'progress'>;

/** An entry with no generation predates the field and is treated as belonging to the current attempt. */
const current = (item: Item, generation: number | undefined) => generation === undefined || generation === item.generation;

const QUESTIONS: Record<JourneyPhase, string> = {
  intake: 'Picked up', spec: 'Specification', plan: 'Plan', build: 'Build', verify: 'Verify', deliver: 'Deliver', review: 'Review', land: 'Land',
};

/** Who decides a phase, in words: the strip's colours, said out loud for the steps. */
function modeWords(mode: string | undefined) {
  switch (mode) {
    case undefined: return 'not set';
    case 'off': return 'off';
    case 'approve': return 'asks a person';
    case 'draft_pr': return 'opens a draft pull request';
    case 'pr': return 'opens a pull request';
    case 'auto_if_green': return 'merges when checks pass';
    default: return 'runs by itself';
  }
}

/** A cell becomes a step state: the factory-step vocabulary plus the three states a run adds. */
function stepState(cell: Cell) {
  if (cell === 'done' || cell === 'now-done') return 'done';
  if (cell === 'now-waiting' || cell === 'now-running') return 'editing';
  if (cell === 'now-blocked') return 'missing';
  if (cell === 'now-failed') return 'failed';
  if (cell === 'now-stopped' || cell === 'now-ignored') return 'stopped';
  return 'later';
}

function Intake({ item }: { item: Item }) {
  return <>
    {item.profile ? <p className="factory-note">Profile <b>{item.profile.name} v{item.profile.version}</b></p> : <p className="factory-note">No profile selected</p>}
    {item.appliedLabels.map(label => {
      const person = item.people?.find(candidate => candidate.providerUserId === label.actorId);
      return <p key={label.label} className="factory-note"><span className="mono">{label.label}</span> added by {person ? actorLabel(person) : 'an unknown person'}
        {' '}<span className="prov-sub">{label.actorId}, {label.origin.toLowerCase().split('_').join(' ')}; profile version {label.profileVersion}</span></p>;
    })}
    {item.ignoredLabels.map(label => <p key={label.label} className="factory-note"><span className="mono">{label.label}</span> ignored: {workReason(label.reason)}</p>)}
  </>;
}

function Prepared({ item, part }: { item: Item; part: 'specification' | 'plan' }) {
  const artifact = item.preparation?.[part];
  if (!artifact) return null;
  return <p className="factory-note">
    <a href={artifact.location.link} target="_blank" rel="noreferrer">{part === 'plan' ? 'Plan' : 'Specification'} #{artifact.location.issueKey}</a>
    {part === 'plan' ? ' · one step' : ''} · pinned <span className="mono">{artifact.sha256.slice(0, 12)}</span>
  </p>;
}

function Decisions({ item, phase }: { item: Item; phase: string }) {
  const resolved = item.events.filter(event => event.type === 'GATE_RESOLVED' && event.phase === phase && current(item, event.generation));
  const earlier = item.events.filter(event => event.type === 'GATE_RESOLVED' && event.phase === phase && !current(item, event.generation)).length;
  const open = item.gate?.state === 'OPEN' && item.gate.phase === phase;
  return <>
    {open && <p className="factory-note">Waiting for the {phase} decision.</p>}
    {resolved.map(event => <p key={event.sequence} className="factory-note">{phase} decision: {event.gateState} by {event.resolver}</p>)}
    {earlier > 0 && <p className="prov-sub">{earlier === 1 ? '1 decision' : `${earlier} decisions`} from an earlier attempt, in the history below</p>}
  </>;
}

function Build({ item }: { item: Item }) {
  const all = item.builds ?? [];
  const builds = all.filter(build => current(item, build.generation));
  const earlier = all.length - builds.length;
  const recorded = item.progress?.execution;
  const execution = recorded && current(item, recorded.build.generation) ? recorded : null;
  return <>
    <p className="factory-note">Runs recorded: {builds.filter(build => build.runId !== null).length}</p>
    {earlier > 0 && <p className="prov-sub">{earlier === 1 ? '1 dispatch' : `${earlier} dispatches`} from an earlier attempt</p>}
    {builds.length > 0 && <ul className="factory-runs" aria-label="Build dispatches">{builds.map(build => <li key={build.attemptId}>
      {build.runId ? <Link to={`/runs/${encodeURIComponent(build.runId)}`}>Open run</Link> : 'Build dispatch pending'} · {build.state}
      {build.reason && <> · {workReason(build.reason)}</>}
    </li>)}</ul>}
    {execution && <p className="factory-note">Built commit <code>{execution.head}</code>{execution.pullRequest ? '' : ' · held, not pushed'}</p>}
  </>;
}

function Delivery({ item, phase }: { item: Item; phase: 'verify' | 'deliver' | 'review' }) {
  const execution = item.progress?.execution;
  if (!execution || !current(item, execution.build.generation)) return null;
  if (phase === 'verify') return <p className="factory-note">{execution.verificationAttempt ? 'Verification recorded' : 'Verification not recorded'}</p>;
  if (phase === 'review') return execution.reviewId ? <p className="factory-note">Review recorded for this build.</p> : null;
  const pr = execution.pullRequest;
  if (!pr) return null;
  const name = pr.draft === true ? 'Draft pull request' : pr.draft === false ? 'Pull request' : 'Pull request · draft state unknown';
  return <p className="factory-note"><a href={pr.url} target="_blank" rel="noreferrer">{name} #{pr.number}</a></p>;
}

function Evidence({ item, phase }: { item: Item; phase: JourneyPhase }) {
  switch (phase) {
    case 'intake': return <Intake item={item} />;
    case 'spec': return <Prepared item={item} part="specification" />;
    case 'plan': return <><Prepared item={item} part="plan" /><Decisions item={item} phase="plan" /></>;
    case 'build': return <Build item={item} />;
    case 'land': return <Decisions item={item} phase="land" />;
    default: return <Delivery item={item} phase={phase} />;
  }
}

/**
 * The item as its journey: eight numbered steps in order, like the repository's factory setup. Done
 * steps carry their proof, the current step carries why it stands where it does and what a person can
 * do, and later steps say who will decide them. The page is the answer to "where is this and what next".
 */
export default function WorkItemSteps({ item, current: actions }: { item: Item; current: ReactNode }) {
  const cells = journeyCells(item);
  const hasCurrent = cells.some(cell => cell.startsWith('now-'));
  return <><ol className="factory-steps work-steps" aria-label="Journey">
    {JOURNEY.map((phase, index) => {
      const cell = cells[index];
      const now = cell.startsWith('now-');
      const later = cell.startsWith('later-');
      return <li key={phase} className={`factory-step ${stepState(cell)}`} aria-label={`Step ${index + 1}: ${QUESTIONS[phase]}`} aria-current={now ? 'step' : undefined}>
        <span className="factory-num" aria-hidden="true">{index + 1}</span>
        <div className="factory-card">
          <div className="factory-top">
            <span className="factory-q">{QUESTIONS[phase]}</span>
            <span className="factory-k">{phase} · {modeWords(item.effectiveModes?.[phase.toUpperCase()])}</span>
          </div>
          {!later && <div className="factory-body">
            {now && <p className="work-reason">{workReason(item.reason)}</p>}
            <Evidence item={item} phase={phase} />
            {now && actions}
          </div>}
        </div>
      </li>;
    })}
  </ol>
  {!hasCurrent && <div className="work-actions work-finished" aria-label="After the journey">{actions}</div>}
  </>;
}
