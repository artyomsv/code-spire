import { useEffect, useRef, useState } from 'react';
import { getWorkItemTracker } from '../../api';

/** Tracker reads in flight at once. A page of fifty rows must not become fifty parallel forge calls. */
const PARALLEL_READS = 3;

/**
 * Ticket titles for the rows on screen, read from the tracker and never stored (ADR-043). A title is
 * read once per mounted list and kept for its later refreshes; a row whose read fails keeps its key,
 * which is what it showed before titles existed.
 */
export function useTicketTitles(ids: readonly string[]): ReadonlyMap<string, string> {
  const [titles, setTitles] = useState<ReadonlyMap<string, string>>(new Map());
  const asked = useRef(new Set<string>());
  const live = useRef(true);
  useEffect(() => { live.current = true; return () => { live.current = false; }; }, []);
  const wanted = ids.filter(id => !asked.current.has(id)).join('\n');

  useEffect(() => {
    const queue = wanted ? wanted.split('\n') : [];
    queue.forEach(id => asked.current.add(id));
    async function drain() {
      for (let id = queue.shift(); id !== undefined && live.current; id = queue.shift()) {
        const read = id;
        try {
          const ticket = await getWorkItemTracker(read);
          if (live.current) setTitles(previous => new Map(previous).set(read, ticket.title));
        } catch { /* the row keeps its key */ }
      }
    }
    for (let worker = 0; worker < PARALLEL_READS; worker++) void drain();
  }, [wanted]);

  return titles;
}
