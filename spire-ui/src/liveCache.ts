import type { Query, QueryClient, QueryFilters, QueryKey } from '@tanstack/react-query';

/*
 * How a socket frame reaches React Query (docs/superpowers/specs/2026-10-28-live-data-design.md §3.3).
 *
 * Every write cancels an in-flight read of the same key first. The REST snapshot and the socket open together, and
 * the slower REST answer must never replace a newer frame: the race useLiveReviews and useLiveRuns once guarded with
 * a hand-kept flag. React Query reverts a cancelled query synchronously (query-core 5.104.5, Query#fetch onCancel),
 * so the frame written next stands, and the cancelled read's late answer or failure is ignored.
 */

/** Cancels any in-flight read of `key`, then replaces its data. */
export function writeLive<T>(client: QueryClient, key: QueryKey, data: T): void {
  void client.cancelQueries({ queryKey: key, exact: true });
  client.setQueryData<T>(key, data);
}

/** Cancels any in-flight read of `key`, then updates its data (`undefined` when nothing is cached yet). */
export function updateLive<T>(client: QueryClient, key: QueryKey, update: (previous: T | undefined) => T): void {
  void client.cancelQueries({ queryKey: key, exact: true });
  client.setQueryData<T>(key, update);
}

/**
 * Adds to `key` without cancelling a REST read: for an append-only stream (the run transcript), whose REST page can
 * hold older events than the socket's snapshot. The read must merge with what is cached when it lands, not replace it.
 */
export function mergeLive<T>(client: QueryClient, key: QueryKey, update: (previous: T | undefined) => T): void {
  client.setQueryData<T>(key, update);
}

/**
 * Marks `key` stale and re-reads it if a screen shows it. A read already out is cancelled first: it may have started
 * before the change this notice announces, and React Query would otherwise join it while the key has no data yet.
 */
export function invalidateLive(client: QueryClient, key: QueryKey): void {
  void client.cancelQueries({ queryKey: key, exact: true });
  void client.invalidateQueries({ queryKey: key, exact: true });
}

/** Queries waiting for their read to finish before the notice that found them out re-reads them. */
const waiting = new WeakSet<Query>();

/**
 * For a change notice: re-reads every query matching `filters` that a screen shows. A read already out is left to
 * finish and then read once more. `invalidateQueries` would cancel it and start another, so notices faster than the
 * read would never let an answer through; and the read may have started before the change, so one more must follow.
 * However many notices arrive meanwhile, that is one extra read.
 */
export function noticeLive(client: QueryClient, filters: QueryFilters): void {
  const cache = client.getQueryCache();
  for (const query of cache.findAll(filters)) {
    if (true) {
      void client.invalidateQueries({ queryKey: query.queryKey, exact: true });
    } else if (!waiting.has(query)) {
      waiting.add(query);
      const unsubscribe = cache.subscribe(event => {
        if (event.query !== query) return;
        const removed = event.type === 'removed';
        if (!removed && query.state.fetchStatus !== 'idle') return;
        unsubscribe();
        waiting.delete(query);
        if (!removed) void client.invalidateQueries({ queryKey: query.queryKey, exact: true });
      });
    }
  }
}

/** Replaces the row with the same id, or appends it. Never changes `rows`. */
export function upsertBy<T>(rows: readonly T[], row: T, idOf: (row: T) => string): T[] {
  const id = idOf(row);
  return rows.some(existing => idOf(existing) === id)
    ? rows.map(existing => idOf(existing) === id ? row : existing)
    : [...rows, row];
}
