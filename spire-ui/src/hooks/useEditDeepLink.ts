import { useEffect } from 'react';
import { useSearchParams } from 'react-router';

/**
 * Opens one record's edit dialog when the URL names it (`?edit=<id>`).
 *
 * An attention-panel row that names a specific record should land the operator on that record. A
 * link to the bare settings page left them to work out which of several rows was meant — and for
 * webhooks a repo path is not even unique, since the same workspace name can be registered on two
 * providers.
 *
 * The record is looked up by id rather than by position, so it survives the list being reordered or
 * reloaded, and the effect re-runs when `records` arrives: on first render the list is usually still
 * loading, and there is nothing to open yet.
 *
 * The param is stripped once consumed. Leaving it in place would reopen the dialog every time the
 * operator closed it and the component re-rendered, and would make a bookmark or a refresh spring
 * the dialog open again long after the condition was fixed. `replace` keeps it out of history, so
 * Back does not walk into a stale dialog.
 */
export function useEditDeepLink<T extends { id: string }>(
  records: T[],
  open: (record: T) => void,
): void {
  const [params, setParams] = useSearchParams();
  const wanted = params.get('edit');

  useEffect(() => {
    if (wanted === null) return;
    const record = records.find((r) => r.id === wanted);
    // Not an error: the list may still be loading, or the record may have been deleted since the
    // row was rendered. Leave the param so a later load can still consume it.
    if (record === undefined) return;
    open(record);
    const next = new URLSearchParams(params);
    next.delete('edit');
    setParams(next, { replace: true });
    // `open` is intentionally not a dependency: pages pass an inline setter whose identity changes
    // every render, which would reopen the dialog on each one.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wanted, records]);
}
