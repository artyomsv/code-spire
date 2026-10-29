import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useLiveSocket, type LiveSocketState } from '../../hooks/useLiveSocket';
import { noticeLive } from '../../liveCache';
import { workKeys } from './workQueries';

/** `{"changed":["<id>",…]}` names the items that changed; `{"changed":"*"}` says any may have. */
function changedIds(frame: unknown): readonly string[] | '*' | null {
  if (typeof frame !== 'object' || frame === null || !('changed' in frame)) return null;
  const { changed } = frame;
  if (changed === '*') return '*';
  return Array.isArray(changed) && changed.every(id => typeof id === 'string') ? changed : null;
}

/** Every work-item read but the tracker, which reads the forge: no database notice covers it. */
function rereadAll(client: QueryClient) {
  noticeLive(client, { queryKey: workKeys.all, predicate: query => query.queryKey[1] !== 'tracker' });
}

/**
 * Follows `/api/ws/work-items` (docs/superpowers/specs/2026-10-28-live-data-design.md §3.3, §4.3): a notice re-reads
 * the named items, their drafts and their plan steps, and every list page, since a change can move an item between
 * filters. The server sends nothing on open and a socket that was down missed its notices, so every open re-reads
 * everything.
 */
export function useWorkItemChanges({ enabled = true }: { enabled?: boolean } = {}): LiveSocketState {
  const client = useQueryClient();
  return useLiveSocket({
    path: '/api/ws/work-items',
    enabled,
    onOpen: () => rereadAll(client),
    onFrame: (frame) => {
      const changed = changedIds(frame);
      if (changed === '*') return rereadAll(client);
      if (changed === null) return;
      noticeLive(client, { queryKey: workKeys.lists() });
      for (const id of changed) {
        noticeLive(client, { queryKey: workKeys.item(id) });
        noticeLive(client, { queryKey: workKeys.drafts(id) });
        noticeLive(client, { queryKey: workKeys.steps(id) });
      }
    },
  });
}
