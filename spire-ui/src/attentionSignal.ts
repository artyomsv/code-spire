/**
 * A nudge telling the attention panel to re-read now.
 *
 * The panel polls, which is right for conditions that change on their own — a review stalling, a
 * webhook starting to fail. It is wrong for the moment the operator has just fixed something: they
 * press Check, it succeeds, and the row can sit there for the rest of the poll interval, which reads
 * as the panel being broken. Refreshing on a real signal beats shortening the interval, which would
 * only narrow the window while multiplying requests.
 *
 * Deliberately a bare module-level signal rather than a context: the publishers are `api.ts`
 * functions with no component to read a context from, and the only subscriber is one always-mounted
 * hook. A provider would add wiring without adding capability.
 */
const listeners = new Set<() => void>();

/** @returns an unsubscribe function, for an effect's cleanup. */
export function onAttentionChanged(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Announce that something which could raise or clear a row has changed. Never throws: a listener
 * blowing up must not fail the mutation the caller actually came to perform.
 */
export function notifyAttentionChanged(): void {
  for (const listener of listeners) {
    try {
      listener();
    } catch {
      // A stale or broken subscriber is not the caller's problem.
    }
  }
}
