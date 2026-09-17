import { AlertTriangle, Check } from 'lucide-react';
import { actorLabel } from '../../actorsApi';
import type { Policy } from '../../work-items/workPolicyApi';
import type { WorkSource } from '../../work-items/workSourcesApi';
import PhaseStrip from '../../work-items/PhaseStrip';
import { clamped, effectiveModes, type Readiness } from './factoryModel';

const trackers: Record<WorkSource['type'], string> = { GITHUB: 'GitHub', GITLAB: 'GitLab', JIRA: 'Jira' };

/** The first missing part decides the message: fixing a later one first would still admit nothing. */
function blocker(ready: Readiness) {
  if (!ready.source) return 'Nothing reads tickets for this repository yet. Add where tickets come from in step 1.';
  if (!ready.people) return 'Tickets are read, but nobody is allowed to start work, so every label is ignored. Add a person in step 2.';
  if (!ready.ceiling) return 'This repository has no ceiling, so every label is refused. Choose one in step 3.';
  return 'No label starts work yet. Map a label in step 4, or use the presets.';
}

const joined = (words: string[]) => words.length < 2 ? words.join('') : `${words.slice(0, -1).join(', ')} or ${words[words.length - 1]}`;

/**
 * What this setup does, in one sentence, above the parts that make it. Operators could configure every
 * part and still not see how the four combine; the consequence is the thing they came to check.
 */
export default function FactoryOutcome({ ready, sources, policy, build }: { ready: Readiness; sources: WorkSource[]; policy: Policy | null; build?: { revision: number } }) {
  if (!ready.ready || !policy) {
    return <div className="factory-outcome blocked" role="status">
      <span className="mark"><AlertTriangle size={14} aria-hidden="true" /></span>
      <div><div className="t">Not ready — no ticket can start work</div><p>{blocker(ready)}</p></div>
    </div>;
  }
  const reading = sources.filter(source => source.enabled && source.allowedPeople.length > 0);
  const people = [...new Map(reading.flatMap(source => source.allowedPeople).map(person => [person.providerUserId, person])).values()];
  const places = reading.map(source => `${trackers[source.type]} ${source.type === 'JIRA' ? 'project' : 'issues in'} ${source.scope}`);
  return <div className="factory-outcome ready" role="status">
    <span className="mark"><Check size={14} aria-hidden="true" /></span>
    <div>
      <div className="t">Ready — tickets can start work</div>
      <p>When <b>{joined(people.map(actorLabel))}</b> adds one of these labels to an open ticket in <b>{joined(places)}</b>, the factory
        picks it up and runs the profile the label names — never more than the ceiling <b>{policy.ceiling!.name} v{policy.ceiling!.version}</b>.</p>
      {/* Admission does not need the build setup, so a missing one is a sentence here rather than a
          blocked banner: every ticket still starts, and a person still types its build coordinates. */}
      {build && build.revision === 0 && <p>Each ticket still needs its branch, harness and model typed by hand. Set them once in step 5.</p>}
      <ul className="factory-runs">{Object.entries(policy.mappings).map(([label, profile]) => <li key={label}>
        <span className="chip mono">{label}</span><span className="factory-arrow" aria-hidden="true">→</span>
        <span>{profile.name} v{profile.version}</span><PhaseStrip modes={effectiveModes(profile, policy.ceiling)} />
        {clamped(profile, policy.ceiling) && <span className="prov-sub">cut back to the ceiling</span>}
      </li>)}</ul>
    </div>
  </div>;
}
