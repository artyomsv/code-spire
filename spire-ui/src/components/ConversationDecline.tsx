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

/** The replies a thread still waits on: by comment id, plus a count of those stored without one (before V122). */
interface PendingReplies {
  waiting: Set<string>;
  ended: Set<string>;
  unnamed: number;
}

function askReply(pending: PendingReplies, reply: ReviewEvent): void {
  if (!reply.commentId) pending.unnamed += 1;
  // A redelivered reply the bot already answered or declined is not waiting again.
  else if (!pending.ended.has(reply.commentId)) pending.waiting.add(reply.commentId);
}

function endReply(pending: PendingReplies, ending: ReviewEvent): void {
  if (ending.commentId) pending.ended.add(ending.commentId);
  if (ending.commentId && pending.waiting.delete(ending.commentId)) return;
  // An ending with no id, or of a reply stored without one: the order rule. An answer ends every earlier reply, a
  // decline one of them.
  if (ending.type === 'FollowUpGenerated') pending.unnamed = 0;
  else pending.unnamed = Math.max(0, pending.unnamed - 1);
}

/**
 * Whether a person's reply still waits for the bot. Each reply carries its comment id, and so do the answer and the
 * decline that end it (V122): a reply waits until an ending with its own id, so a late decline of an older reply never
 * ends a newer one (code-quality/M-1). Events stored without an id fall back to their order.
 */
export function hasPendingReply(threadEvents: ReviewEvent[]): boolean {
  const pending: PendingReplies = { waiting: new Set(), ended: new Set(), unnamed: 0 };
  for (const e of threadEvents) {
    if (e.type === 'AuthorReplied') askReply(pending, e);
    else if (e.type === 'FollowUpGenerated' || e.type === 'FollowUpDeclined') endReply(pending, e);
  }
  return pending.waiting.size > 0 || pending.unnamed > 0;
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
