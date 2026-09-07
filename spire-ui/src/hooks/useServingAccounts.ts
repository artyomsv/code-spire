import { useEffect, useState } from 'react';
import { fetchServingAccounts, type ServingAccounts, type WebhookRepoView } from '../api';

export interface ServingLookup {
  data?: ServingAccounts;
  error?: string;
}

/** The workspace a registration belongs to — the owner segment, or the whole target for an org row. */
export function ownerOf(w: Pick<WebhookRepoView, 'scope' | 'target'>): string {
  return w.scope === 'org' ? w.target : w.target.split('/')[0];
}

/** One key per (forge, owner). Neither can contain a slash: the owner is the segment before one. */
export function servingKey(type: string, owner: string): string {
  return `${type}/${owner}`;
}

/**
 * Which accounts serve each registration's forge and workspace, asked of the orchestrator once per
 * distinct pair rather than once per row: the answer is keyed on (forge, workspace), and a page of
 * twenty repositories in one organization is one request. A failed request is kept as an error so
 * the cell can say "unknown" — never "none", which is the answer that would send an operator to
 * register an account they already have.
 */
export function useServingAccounts(repos: WebhookRepoView[]): Record<string, ServingLookup> {
  const [lookups, setLookups] = useState<Record<string, ServingLookup>>({});

  useEffect(() => {
    let alive = true;
    const pairs = new Map<string, { type: string; owner: string }>();
    for (const w of repos) {
      const owner = ownerOf(w);
      pairs.set(servingKey(w.providerType, owner), { type: w.providerType, owner });
    }
    setLookups({});
    for (const [key, { type, owner }] of pairs) {
      fetchServingAccounts(type, owner)
        .then((data) => {
          if (alive) setLookups((prev) => ({ ...prev, [key]: { data } }));
        })
        .catch((err: unknown) => {
          if (alive) setLookups((prev) => ({ ...prev, [key]: { error: err instanceof Error ? err.message : String(err) } }));
        });
    }
    return () => {
      alive = false;
    };
  }, [repos]);

  return lookups;
}
