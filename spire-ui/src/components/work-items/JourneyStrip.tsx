import { JOURNEY, journeyCells, standing, type WorkItemRow } from './workJourney';

const STANDING_WORDS: Record<string, string> = {
  waiting: 'waiting for a decision', blocked: 'waiting for input', running: 'running', stopped: 'stopped',
  failed: 'failed', ignored: 'not eligible', done: 'finished',
};

/**
 * The eight phases as one row: solid for done, the current phase marked by how it stands, and the
 * rest by who decides them. It answers "how far along is this, and what is it waiting for" in the
 * width of a table cell, where eight words would not fit and could not be compared between rows.
 *
 * <p>The cells are decoration; the strip speaks the whole position once for assistive tech.
 */
export default function JourneyStrip({ item }: { item: Pick<WorkItemRow, 'phase' | 'workflowStatus' | 'effectiveModes'> }) {
  const cells = journeyCells(item);
  const current = Math.min(cells.findIndex(cell => cell.startsWith('now-')), JOURNEY.length - 1);
  const spoken = current < 0 ? 'All phases done'
    : `Phase ${current + 1} of ${JOURNEY.length}: ${JOURNEY[current]}, ${STANDING_WORDS[standing(item.workflowStatus)]}`;
  return <span className="journey" role="img" aria-label={spoken}>
    {cells.map((cell, index) => <span key={JOURNEY[index]} className={`jc ${cell}`} aria-hidden="true" title={`${JOURNEY[index]}: ${cell.replace('-', ' ')}`}>
      {JOURNEY[index][0].toUpperCase()}
    </span>)}
  </span>;
}

/** The key the strip needs. Render it once, under the list it explains. */
export function JourneyLegend() {
  return <div className="phase-legend">
    <span><i className="jc done" aria-hidden="true" />done</span>
    <span><i className="jc now-waiting" aria-hidden="true" />current, needs a person</span>
    <span><i className="jc now-running" aria-hidden="true" />current, running</span>
    <span><i className="jc later-auto" aria-hidden="true" />later, runs by itself</span>
    <span><i className="jc later-ask" aria-hidden="true" />later, asks a person</span>
    <span><i className="jc later-off" aria-hidden="true" />off</span>
  </div>;
}
