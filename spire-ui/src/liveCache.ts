import type { QueryClient, QueryKey } from '@tanstack/react-query';

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
 * Marks `key` stale and re-reads it if a screen shows it. A read already out is cancelled first: it may have started
 * before the change this notice announces, and React Query would otherwise join it while the key has no data yet.
 */
export function invalidateLive(client: QueryClient, key: QueryKey): void {
  void client.cancelQueries({ queryKey: key, exact: true });
  void client.invalidateQueries({ queryKey: key, exact: true });
}

/** Replaces the row with the same id, or appends it. Never changes `rows`. */
export function upsertBy<T>(rows: readonly T[], row: T, idOf: (row: T) => string): T[] {
  const id = idOf(row);
  return rows.some(existing => idOf(existing) === id)
    ? rows.map(existing => idOf(existing) === id ? row : existing)
    : [...rows, row];
}
