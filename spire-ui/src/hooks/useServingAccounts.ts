import { useQueries } from '@tanstack/react-query';
import { fetchServingAccounts, servingAccountsKey, type ServingAccounts, type WebhookRepoView } from '../api';

export interface ServingLookup {
  data?: ServingAccounts;
  error?: string;
}

/** The workspace a registration belongs to — the owner segment, or the whole target for an org row. */
export function ownerOf(w: Pick<WebhookRepoView, 'scope' | 'target'>): string {
  return w.scope === 'org' ? w.target : w.target.split('/')[0];
}

/**
 * One key per (forge, owner). An owner may itself contain a slash — a GitLab nested group registered
 * at organization scope is `group/subgroup` — which is safe, because a key is only ever compared for
 * equality with another key built the same way. Nothing parses one back into its two parts.
 */
export function servingKey(type: string, owner: string): string {
  return `${type}/${owner}`;
}

/**
 * Which accounts serve each registration's forge and workspace, asked of the orchestrator once per
 * distinct pair rather than once per row: the answer is keyed on (forge, workspace), and a page of
 * twenty repositories in one organization is one request. A failed request is kept as an error so
 * the cell can say "unknown" — never "none", which is the answer that would send an operator to
 * register an account they already have.
 *
 * <p>Each repository has its own cache entry, keyed on its id, not on the array that carried it. A save or a delete
 * that rebuilds the array re-asks nothing, and a row added beside answered ones asks only for itself, so no chip
 * flashes back to "loading…". A lookup absent from the record is still loading.
 */
export function useServingAccounts(repos: WebhookRepoView[]): Record<string, ServingLookup> {
  const ids = [...new Set(repos.flatMap((w) => (w.repositoryId ? [w.repositoryId] : [])))].sort();
  const reads = useQueries({
    queries: ids.map((id) => ({ queryKey: servingAccountsKey(id), queryFn: () => fetchServingAccounts(id) })),
  });
  const lookups: Record<string, ServingLookup> = {};
  ids.forEach((id, i) => {
    const read = reads[i];
    if (read.data !== undefined) lookups[id] = { data: read.data };
    else if (read.error) lookups[id] = { error: read.error instanceof Error ? read.error.message : String(read.error) };
  });
  return lookups;
}
