import type { ReviewEvent } from '../api';

/**
 * The stored events of a reply thread's back-and-forth, in order: a person's reply, the bot's answer, and the bot
 * staying quiet (`FollowUpDeclined`, PR #457). A decline is not a message, so it is kept out of the turns that count
 * and pair the thread's messages; it only ends the pending reply and says why.
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

/** The server's detail reads "reply not answered: <why>"; the note keeps only the why. */
function reasonOf(declined: ReviewEvent): string {
  return declined.det.replace(/^reply not answered:\s*/i, '');
}

interface DeclinedNoteProps {
  threadEvents: ReviewEvent[];
}

/**
 * A quiet note under a thread whose latest reply the bot chose not to answer, so the reader sees why no answer came
 * instead of a "responding…" mark that never ends. A later reply makes the thread pending again, and the note goes.
 */
export function DeclinedNote({ threadEvents }: DeclinedNoteProps) {
  const last = lastConversationEvent(threadEvents);
  if (last?.type !== 'FollowUpDeclined') return null;
  return <div className="convo-note convo-declined">Not answered: {reasonOf(last)}</div>;
}
