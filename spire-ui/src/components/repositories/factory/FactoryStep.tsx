import type { ReactNode } from 'react';

export type StepState = 'done' | 'missing' | 'editing';

interface Props {
  number: number;
  /** What the part is for, in the operator's words — the heading a reader scans. */
  question: string;
  /** What the rest of the product calls it, so the vocabulary still connects to docs and logs. */
  term: string;
  state: StepState;
  status?: ReactNode;
  actions?: ReactNode;
  children?: ReactNode;
}

/**
 * One part of a repository's factory setup. The number is real order, not decoration: each part only
 * does something once the one before it is set — no source reads no labels, nobody allowed makes every
 * label ignored, and no ceiling refuses every label mapping.
 */
export default function FactoryStep({ number, question, term, state, status, actions, children }: Props) {
  return <li className={`factory-step ${state}`} aria-label={`Step ${number}: ${question}`}>
    <span className="factory-num" aria-hidden="true">{number}</span>
    <div className="factory-card">
      <div className="factory-top">
        <span className="factory-q">{question}</span><span className="factory-k">{term}</span>
        <span className="grow" />{status}{actions}
      </div>
      {children && <div className="factory-body">{children}</div>}
    </div>
  </li>;
}
