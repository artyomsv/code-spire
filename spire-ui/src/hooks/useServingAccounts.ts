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
 * <p>The effect is keyed on the SET of pairs, not on the array that carried them. Keying it on the
 * array meant every save and delete re-fetched and flashed every chip back to "loading…", and a
 * caller passing an inline literal or a `.filter(…)` result would have rendered forever — this hook
 * is exported, so that caller is a matter of time. When the set does change, every pair is
 * re-fetched: the answers are no longer known to be current, and `setLookups({})` says so.
 */
export function useServingAccounts(repos: WebhookRepoView[]): Record<string, ServingLookup> {
  const [lookups, setLookups] = useState<Record<string, ServingLookup>>({});

  const pairs = new Map<string, { type: string; owner: string }>();
  for (const w of repos) {
    const owner = ownerOf(w);
    pairs.set(servingKey(w.providerType, owner), { type: w.providerType, owner });
  }
  const dep = [...pairs.keys()].sort().join(',');

  useEffect(() => {
    let alive = true;
    // A fresh `{}` is a new reference React cannot bail out of, so clearing an already-empty map
    // would re-render every consumer of this hook for no change.
    setLookups((prev) => (Object.keys(prev).length === 0 ? prev : {}));
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
    // `pairs` is rebuilt every render but its CONTENT is what `dep` encodes, so `dep` alone decides
    // when this must run again.
  }, [dep]);

  return lookups;
}
