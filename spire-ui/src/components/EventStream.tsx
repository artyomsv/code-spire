import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { formatEventTime } from '../format';
import type { ReviewDetail, ReviewEvent } from '../api';

interface Run {
  label: string;
  events: ReviewEvent[];
}

/**
 * Runs are delimited by ReviewRequested. Numbering is computed in the API's chronological order so
 * a run keeps its identity once the list is reversed for display.
 */
export function toRuns(events: ReviewEvent[]): Run[] {
  const runs: Run[] = [];
  for (const e of events) {
    if (e.type === 'ReviewRequested' || runs.length === 0) {
      runs.push({ label: runs.length === 0 ? 'Initial run' : `Re-run ${runs.length}`, events: [] });
    }
    runs[runs.length - 1].events.push(e);
  }
  return runs.reverse();
}

/** Newest-first, collapsible pipeline runs — replaces the unbounded flat event list so the
 *  events an operator is looking for (the latest run) aren't buried below every prior run. */
export default function EventStream({ r }: { r: ReviewDetail }) {
  const runs = toRuns(r.events);
  // Keyed by run label, not display index. ReviewDetail re-renders this component in place on
  // every live update without remounting it, and a new run shifts every older run's index by
  // one — an index-keyed override would silently migrate onto the wrong run. A run's label is
  // its stable identity (computed from chronological position, which only grows).
  const [openOverride, setOpenOverride] = useState<Record<string, boolean>>({});

  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Event stream</h3>
        <span className="badge">this review only</span>
      </div>
      <div className="body">
        {runs.map((run, i) => {
          const isOpen = openOverride[run.label] ?? i === 0;
          return (
            <div className="ev-run" role="group" aria-label={run.label} key={run.label}>
              <button
                className="ev-run-head"
                aria-expanded={isOpen}
                onClick={() => setOpenOverride({ ...openOverride, [run.label]: !isOpen })}
              >
                {isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                <span className="ev-sep-label">{run.label}</span>
                {i === 0 && <span className="badge">latest</span>}
                <span className="muted">{run.events.length} events</span>
              </button>
              {isOpen && (
                <div className="events">
                  {run.events.map((e, j) => (
                    <div className={`ev ${e.lane}`} key={j}>
                      <div className="at">
                        <span className="at-abs">{formatEventTime(e.ts)}</span>
                        <span className="at-rel">{e.at}</span>
                      </div>
                      <div className="what">
                        <span className="lane"></span>
                        <div>
                          <div className="type">{e.type}</div>
                          <div className="det">{e.det}</div>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
