import type { ReviewSummary } from '../api';
import { outcomeOf, providerLabel } from '../render';
import { prStateLabel, providerName, RESPONDING_LABEL } from './ReviewIcons';
import { findingsSummary, pipelineSummary } from './ReviewRowCells';

/**
 * The words a list row's marks dropped, in row order: the status, a reply in progress, the pull request's state, the
 * forge, the pipeline's state and the findings split. The same words the marks give on hover.
 */
export function rowSummaryParts(r: ReviewSummary): string[] {
  const parts = [outcomeOf(r.status, r.findings, r.blockerCount, r.degraded).label];
  if (r.answering) parts.push(RESPONDING_LABEL);
  parts.push(prStateLabel(r.prState));
  const forge = providerName(providerLabel(r));
  if (forge) parts.push(forge);
  parts.push(`Pipeline: ${pipelineSummary(r.status, r.stage)}`);
  const findings = findingsSummary(r.status, r.findings, r.carriedOverFindings, r.degraded);
  if (findings) parts.push(findings);
  return parts;
}

interface Props {
  review: ReviewSummary;
}

/**
 * One line under a list row, shown only while the row has keyboard focus (`.row:focus-visible .row-focus-summary`
 * in index.css): a sighted keyboard user cannot hover the marks, and the row, not each mark, is the tab stop. Hidden
 * from assistive technology, because the row's accessible name already carries the same words.
 */
export default function RowFocusSummary({ review }: Props) {
  return (
    <div className="row-focus-summary" aria-hidden="true">
      {rowSummaryParts(review).join(' · ')}
    </div>
  );
}
