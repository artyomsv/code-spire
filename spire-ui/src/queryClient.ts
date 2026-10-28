import { QueryClient } from '@tanstack/react-query';

/**
 * The dashboard's one server-data cache (docs/superpowers/specs/2026-10-28-live-data-design.md §3.1). One per page
 * load in production, and one per test so no test reads another's answers.
 *
 * No retries: no screen retried a failed read before React Query, and a live screen gets the next socket frame
 * anyway. No re-read on window focus: a live screen is kept fresh by its socket, not by focus.
 */
export function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } });
}
