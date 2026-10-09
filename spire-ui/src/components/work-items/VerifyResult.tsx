import { useState } from 'react';
import { Check, ChevronDown, ChevronRight, CircleHelp, X } from 'lucide-react';
import type { WorkVerification } from './workPreparationApi';
import { workReason } from './workReasons';

/** Three looks that cannot be confused: only PASSED ever carries a tick (spec §5). */
const LOOK = {
  PASSED: { label: 'Passed', tone: 'passed', icon: <Check size={14} aria-hidden="true" /> },
  FAILED: { label: 'Failed', tone: 'failed', icon: <X size={14} aria-hidden="true" /> },
  UNVERIFIED: { label: 'Not checked', tone: 'refused', icon: <CircleHelp size={14} aria-hidden="true" /> },
} as const;

/** The reasons an open verify gate carries when it asks about a result rather than permission to run. */
const RESULT_REASONS = ['verify_failed', 'verify_unverified'];

export function isVerifyResultGate(item: { reason: string; gate?: { phase: string; state: string } | null }) {
  return item.gate?.phase === 'verify' && item.gate.state === 'OPEN' && RESULT_REASONS.includes(item.reason);
}

/** What a verify found: the outcome, why, and each check with its output on demand. */
export default function VerifyResult({ verification }: { verification: WorkVerification }) {
  const look = LOOK[verification.outcome];
  const [open, setOpen] = useState<number | null>(null);
  return <div>
    <p className="factory-note"><span className={`pill ${look.tone}`} aria-label={look.label}>{look.icon} {look.label}</span>
      {verification.reason && <> {workReason(verification.reason)}</>}</p>
    {verification.checks.length > 0 && <table className="prov-table" aria-label="Checks">
      <thead><tr><th>Command</th><th>Exit</th><th>Time</th></tr></thead>
      <tbody>{verification.checks.map((check, index) => <tr key={index}>
        <td><button className="ctx-item-head" type="button" aria-expanded={open === index} onClick={() => setOpen(open === index ? null : index)}>
          {open === index ? <ChevronDown size={14} /> : <ChevronRight size={14} />}<code>{check.command}</code></button>
          {open === index && <pre className="ctx-detail">{check.outputTail || '(no output)'}</pre>}</td>
        <td className="mono">{check.exitCode ?? 'did not run'}</td>
        <td className="mono">{(check.wallMillis / 1000).toFixed(1)} s</td></tr>)}</tbody>
    </table>}
  </div>;
}
