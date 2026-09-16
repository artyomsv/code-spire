import { useEffect, useRef, useState } from 'react';
import { getWorkItemTracker } from '../../api';

/** Tracker reads in flight at once, for the whole list. Fifty rows must not become fifty forge calls. */
const PARALLEL_READS = 3;

/**
 * Ticket titles for the rows on screen, read from the tracker and never stored (ADR-043). A title is
 * read once per mounted list and kept for its later refreshes; a row whose read fails keeps its key.
 *
 * <p>One queue serves the list. Turning a page replaces the rows still waiting with the new page's,
 * so reads for rows nobody is looking at stop being started, and the limit holds across pages.
 */
export function useTicketTitles(ids: readonly string[]): ReadonlyMap<string, string> {
  const [titles, setTitles] = useState<ReadonlyMap<string, string>>(new Map());
  const asked = useRef(new Set<string>());
  const queue = useRef<string[]>([]);
  const running = useRef(0);
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; queue.current = []; }; }, []);
  const key = ids.join('\n');

  useEffect(() => {
    const visible = key ? key.split('\n') : [];
    queue.current = visible.filter(id => !asked.current.has(id));
    async function drain() {
      running.current++;
      try {
        for (let id = queue.current.shift(); id !== undefined && live.current; id = queue.current.shift()) {
          const read = id;
          asked.current.add(read);
          try {
            const ticket = await getWorkItemTracker(read);
            if (live.current) setTitles(previous => new Map(previous).set(read, ticket.title));
          } catch { /* the row keeps its key */ }
        }
      } finally { running.current--; }
    }
    while (running.current < PARALLEL_READS && queue.current.length > 0) void drain();
  }, [key]);

  return titles;
}
