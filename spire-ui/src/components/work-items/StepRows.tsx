import { useState } from 'react';
import type { StepRow, StepSummary } from './workPreparationApi';

interface Props { rows: StepRow[] }

const SECTIONS: { key: keyof StepSummary; label: string }[] = [
  { key: 'decisions', label: 'Decided' },
  { key: 'alternativesRejected', label: 'Rejected' },
  { key: 'deviations', label: 'Deviated from the plan' },
];

const recordedNothing = (summary: StepSummary) => SECTIONS.every(section => summary[section.key].length === 0);

/** The agent's words, rendered as text only: a summary is agent-written and may hold markup. */
function Summary({ summary }: { summary: StepSummary }) {
  return <dl className="step-summary">
    {SECTIONS.filter(section => summary[section.key].length > 0).map(section => <div key={section.key}>
      <dt>{section.label}</dt>
      {summary[section.key].map((item, index) => <dd key={index}>{item}</dd>)}
    </div>)}
  </dl>;
}

function Row({ row }: { row: StepRow }) {
  const [open, setOpen] = useState(false);
  const missing = row.summaryState === 'summary_missing' || (row.summary !== null && recordedNothing(row.summary));
  return <li className={`step-row ${row.state}`}>
    <span>{row.index + 1}. {row.title}</span> · <span className="step-state">{row.state}</span>
    {row.head && <> · <code>{row.head.slice(0, 7)}</code></>}
    {missing && <p className="factory-note">No decisions recorded</p>}
    {!missing && row.summary && <>
      {' '}<button type="button" className="btn-ghost sm" aria-expanded={open} onClick={() => setOpen(!open)}>{open ? 'Hide summary' : 'Show summary'}</button>
      {open && <Summary summary={row.summary} />}
    </>}
  </li>;
}

/**
 * One row per plan step in the Build step (M4 slice 3, §6.10): its title, state, the commit it left, and what its agent
 * said it decided, expandable. A step whose run handed no summary says so plainly.
 */
export default function StepRows({ rows }: Props) {
  if (rows.length === 0) return null;
  return <ol className="factory-runs step-rows" aria-label="Plan steps">
    {rows.map(row => <Row key={row.index} row={row} />)}
  </ol>;
}
