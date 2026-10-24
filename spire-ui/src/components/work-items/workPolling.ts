/** How often an unfinished work item is read again: an agent moves it in seconds (live land acceptance). */
export const ITEM_POLL_MILLISECONDS = 3_000;
/** How often the list is read again while it holds unfinished work, or nothing yet. */
export const LIST_POLL_MILLISECONDS = 5_000;
/** Finished work changes rarely, by a re-admission or a late ticket comment, so it is read every 15 s. */
export const FINISHED_POLL_MILLISECONDS = 15_000;

const FINISHED = new Set(['completed', 'stopped', 'retired']);

/** Whether a work item with this status has finished: nothing moves it on by itself. */
export function isFinished(status: string) {
  return FINISHED.has(status);
}
