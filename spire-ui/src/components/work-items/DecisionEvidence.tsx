import type { WorkItemDetail } from '../../api';
import { formatEventTime } from '../../format';
import { formatCost } from '../../money';
import CopyField from '../CopyField';
import type { Approval } from './approvalsApi';
import { workRefusal } from './workReasons';
import type { PreparationEvidence } from './workPreparationApi';

interface Props {
  item: WorkItemDetail;
  approval: Approval;
  evidence: PreparationEvidence | null;
  evidenceError: string;
}

const HOUR_MILLISECONDS = 3_600_000;

function hours(seconds: number) {
  const value = seconds / 3600;
  return value === 1 ? '1 hour' : `${Number.isInteger(value) ? value : value.toFixed(1)} hours`;
}

/** "in 23 h", or "expired" — relative, because an absolute timestamp does not say whether to hurry. */
function expiresIn(iso: string, now: number) {
  const left = new Date(iso).getTime() - now;
  if (left <= 0) return 'expired';
  return left < HOUR_MILLISECONDS ? `in ${Math.max(1, Math.round(left / 60_000))} min` : `in ${Math.round(left / HOUR_MILLISECONDS)} h`;
}

/**
 * The parts of a decision an approver has to read before answering: what is bound, what approving
 * starts, how long it stays open and where else it can be answered. Every value is the recorded one;
 * a ticket that moved since registration is named instead of shown, so its unapproved text never
 * reads as the evidence.
 */
export default function DecisionEvidence({ item, approval, evidence, evidenceError }: Props) {
  const { gate } = approval;
  const preparation = item.preparation;
  const limits = item.effectiveLimits;
  const expiry = expiresIn(gate.expiresAt, Date.now());
  return <div className="decision">
    {preparation && <section className="decision-sec" aria-label="What you approve">
      <h4>What you approve</h4>
      {evidenceError && <p className="prov-error" role="alert">The tickets could not be read: {evidenceError}</p>}
      {evidence?.reason && <p className="prov-error" role="alert">{workRefusal(evidence.reason, evidence.detail)}</p>}
      <dl className="panel-grid decision-facts">
        <div><dt>Specification</dt><dd className="v"><a href={preparation.specification.location.link} target="_blank" rel="noreferrer">#{preparation.specification.location.issueKey}</a></dd></div>
        <div><dt>Starts from</dt><dd className="v mono">{preparation.baseBranch} @ {preparation.baseCommit.slice(0, 7)}</dd></div>
        <div><dt>Agent</dt><dd className="v">{preparation.harness} · {preparation.model}</dd></div>
        <div><dt>Plan</dt><dd className="v"><a href={preparation.plan.location.link} target="_blank" rel="noreferrer">#{preparation.plan.location.issueKey}</a> · one step</dd></div>
      </dl>
      {evidence?.specification && <blockquote className="decision-quote" aria-label="Specification text">{evidence.specification}</blockquote>}
      {evidence?.instruction && <blockquote className="decision-quote step" aria-label="The step the build runs">{evidence.instruction}</blockquote>}
    </section>}
    <section className="decision-sec" aria-label="If you approve">
      <h4>If you approve</h4>
      {gate.phase === 'plan' && limits
        ? <p>One build starts. It stops at <b>{formatCost(limits.maxCostMillicents)}</b> or after <b>{hours(limits.maxWallClockSeconds)}</b>, and this item may use up to <b>{limits.maxRunsPerItem}</b> runs.</p>
        : <p>The {gate.phase} phase continues within this item's limits.</p>}
    </section>
    <section className="decision-sec" aria-label="Time">
      <h4>Time</h4>
      <p>Opened {formatEventTime(gate.openedAt)} · <span className={expiry === 'expired' || expiry.endsWith('min') ? 'chip warn' : 'chip'}>{expiry === 'expired' ? 'expired' : `expires ${expiry}`}</span></p>
    </section>
    {approval.trackerCommand && <section className="decision-sec" aria-label="Other ways to answer">
      <h4>Or answer on the ticket</h4>
      <CopyField label="Tracker command" value={approval.trackerCommand} hint="Allowed people can post this on the ticket. Use /reject with the same values to refuse." />
      {gate.phase === 'land' && <p className="prov-note">{approval.prReviewAvailable ? 'Approving the pull request review also answers this decision.' : approval.prReviewDetail}</p>}
    </section>}
  </div>;
}
