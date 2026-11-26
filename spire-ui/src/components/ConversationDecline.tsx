import type { ReviewEvent } from '../api';

/**
 * The stored events of a reply thread's back-and-forth, in order: a person's reply, the bot's answer, and the bot
 * staying quiet (`FollowUpDeclined`, PR #457). A decline is not a message, so it is kept out of the turns that count
 * and pair the thread's messages; it only ends one pending reply and says why.
 */
const CONVERSATION_TYPES = new Set(['AuthorReplied', 'FollowUpGenerated', 'FollowUpDeclined']);

/** Every conversation event stored under `threadRef`, declines included, in the order they happened. */
export function threadEventsOf(events: ReviewEvent[], threadRef: string): ReviewEvent[] {
  return events.filter((e) => e.threadRef === threadRef && CONVERSATION_TYPES.has(e.type));
}

/** The thread's messages only: what counts a thread's replies and pairs them with the forge's transcript. */
export function withoutDeclines(threadEvents: ReviewEvent[]): ReviewEvent[] {
  return threadEvents.filter((e) => e.type !== 'FollowUpDeclined');
}

/** The last conversation event of a thread, or undefined when there is none. */
export function lastConversationEvent(threadEvents: ReviewEvent[]): ReviewEvent | undefined {
  const conversation = threadEvents.filter((e) => CONVERSATION_TYPES.has(e.type));
  return conversation[conversation.length - 1];
}

/**
 * Whether a person's reply still waits for the bot. Since its last answer, every reply waits until a decline ends one:
 * the server records a decline only for a reply it was still answering, once (code-quality/M-1). So two replies and one
 * late decline of the first leave the second pending, whatever order they were stored in.
 */
export function hasPendingReply(threadEvents: ReviewEvent[]): boolean {
  let replies = 0;
  for (const e of threadEvents) {
    if (e.type === 'FollowUpGenerated') replies = 0;
    else if (e.type === 'AuthorReplied') replies += 1;
    else if (e.type === 'FollowUpDeclined') replies = Math.max(0, replies - 1);
  }
  return replies > 0;
}

/** The server's detail reads "reply not answered: <why>"; the note keeps only the why. */
function reasonOf(declined: ReviewEvent): string {
  return declined.det.replace(/^reply not answered:\s*/i, '');
}

interface DeclinedNoteProps {
  threadEvents: ReviewEvent[];
}

/**
 * A quiet note under a thread whose latest reply the bot chose not to answer, so the reader sees why no answer came
 * instead of a "responding…" mark that never ends. While another reply still waits, or after a later one, the thread
 * is pending and the note goes.
 */
export function DeclinedNote({ threadEvents }: DeclinedNoteProps) {
  const last = lastConversationEvent(threadEvents);
  if (last?.type !== 'FollowUpDeclined' || hasPendingReply(threadEvents)) return null;
  return <div className="convo-note">Not answered: {reasonOf(last)}</div>;
}
