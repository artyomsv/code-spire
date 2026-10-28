import { QueryClient } from '@tanstack/react-query';

/**
 * The dashboard's server-state cache. One per page load in production, and one per test so no test
 * reads another's answers.
 *
 * No retries: before React Query no screen retried a failed read, and a screen that polls already tries
 * again on its next tick. Retrying would also hide a failure from the person looking at the page for
 * seconds, behind a spinner that says nothing.
 */
export function createQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}
