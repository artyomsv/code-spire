import { useLayoutEffect, useRef } from 'react';
import { hashKey, useQueryClient, type QueryKey } from '@tanstack/react-query';

/**
 * Runs `effect` with the data each time a read of `key` completes: a read that answered (`'success'`), or one that failed
 * while the last good answer is still on screen (`'error'`, with that answer). Never for a cache write (`setQueryData`)
 * or a cancelled read.
 *
 * <p>For work a page does per read, such as checking each listed account's connection. A check that writes its result
 * into the list must not start another round of checks, so this follows the cache's own events rather than the data:
 * a write is a `success` marked `manual`, and a cancel is a `setState`. It also sees a read that settles between two
 * renders, which a watch on `isFetching` would miss. Subscribed in a layout effect, before the first read starts.
 */
export function useEachRead<T>(key: QueryKey, effect: (data: T, outcome: 'success' | 'error') => void): void {
  const client = useQueryClient();
  const latest = useRef(effect);
  latest.current = effect;
  const hash = hashKey(key);
  useLayoutEffect(() => client.getQueryCache().subscribe(event => {
    if (event.type !== 'updated' || event.query.queryHash !== hash) return;
    const { action } = event;
    const data = event.query.state.data as T | undefined;
    if (action.type === 'success' && !action.manual) latest.current(data as T, 'success');
    else if (action.type === 'error' && data !== undefined) latest.current(data, 'error');
  }), [client, hash]);
}
