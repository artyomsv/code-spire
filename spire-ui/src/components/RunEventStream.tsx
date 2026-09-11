import { useLayoutEffect, useRef, useState } from 'react';
import { useRunTranscript } from '../hooks/useRunTranscript';
import { formatEventTime } from '../format';
import RunCard from './RunCard';

const AGENT_KINDS = new Set(['THINKING', 'OUTPUT', 'TOOL_USE', 'TOOL_RESULT']);
const FILTERS = ['All', 'Agent', 'System', 'Errors'] as const;
type Filter = typeof FILTERS[number];

export default function RunEventStream({ runId }: { runId: string }) {
  const { events, dropped, loading, error } = useRunTranscript(runId);
  const [filter, setFilter] = useState<Filter>('All');
  const [tail, setTail] = useState(true);
  const viewport = useRef<HTMLDivElement>(null);
  const visible = events.filter((event) => {
    if (filter === 'Agent') return AGENT_KINDS.has(event.kind);
    if (filter === 'System') return !AGENT_KINDS.has(event.kind);
    if (filter === 'Errors') return event.error;
    return true;
  });
  useLayoutEffect(() => {
    if (tail && viewport.current) viewport.current.scrollTop = viewport.current.scrollHeight;
  }, [events, filter, tail]);

  return <RunCard title="Event stream">
    <div className="run-event-controls">
      <div role="group" aria-label="Event filters">
        {FILTERS.map((value) => <button key={value} type="button" className="btn"
          aria-pressed={filter === value} onClick={() => setFilter(value)}>{value}</button>)}
      </div>
      <button type="button" className="btn" aria-pressed={tail}
        title="Auto-scroll the tail as new events arrive" onClick={() => setTail(!tail)}>Tail</button>
    </div>
    {dropped && <p className="prov-sub">Earlier events were dropped from this view. Read earlier events in the{' '}
      <a href={`/api/runs/${encodeURIComponent(runId)}/transcript?limit=2000&before=${events[0].sequence}`}
        target="_blank" rel="noreferrer">REST transcript</a>.</p>}
    {error && <p role="alert">{error}</p>}
    {loading && <p className="prov-sub">Loading events…</p>}
    {!loading && !error && visible.length === 0 && <p className="prov-sub">
      {events.length === 0 ? 'No events yet' : 'No matching events'}
    </p>}
    <div ref={viewport} className="run-event-viewport" role="region" aria-label="Run events" tabIndex={0}
      onScroll={(event) => {
        const el = event.currentTarget;
        setTail(el.scrollHeight - el.scrollTop - el.clientHeight <= 4);
      }}>
      <ol className="run-events">{visible.map((event) => <li key={event.sequence} className="run-event">
        <div className="run-event-meta"><span className="mono">#{event.sequence}</span>
          <time dateTime={event.at}>{formatEventTime(event.at)}</time><span>{event.kind}</span>
          {event.error && <span className="run-event-error">Error</span>}
        </div>
        <pre>{event.text}</pre>
      </li>)}</ol>
    </div>
  </RunCard>;
}
