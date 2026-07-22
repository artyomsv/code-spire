import { useEffect, useRef, useState, type ReactNode, type SyntheticEvent } from 'react';
import { Bot, CheckCircle2, ChevronDown, MessageCircle, MessagesSquare } from 'lucide-react';
import { fetchThreadMessages, type ReviewEvent, type ThreadMessage } from '../api';
import { formatEventTime } from '../format';
import { MessageText } from './MessageText';

interface FindingConversationProps {
  workspace: string;
  slug: string;
  pr: number;
  threadRef: string;
  previewTurns: ReviewEvent[]; // stored events for this thread — count the toggle and supply timestamps
  previewBody: ReactNode; // the ≤160-char preview exchanges, shown until (or unless) the full thread loads
  resolved?: boolean; // re-review reconciliation says this thread's finding was closed out
}

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'loaded'; messages: ThreadMessage[] }
  | { status: 'error' };

/** Whether the thread needs a (re)fetch: the panel is open and either nothing has been loaded
 *  yet, or it was loaded at a reply count that's now stale (a new turn arrived since). */
export function needsFetch(open: boolean, loadedAtCount: number | null, replyCount: number): boolean {
  return open && loadedAtCount !== replyCount;
}

/**
 * Fetch the thread via {@code fetchThread} when {@link needsFetch} says to; otherwise resolve to
 * {@code null} without calling it. Factored out of the effect so the re-fetch trigger — including
 * "a new turn arrived while the thread was open" — is directly testable with a mocked fetcher.
 */
export async function refreshThread(
  open: boolean,
  replyCount: number,
  loadedAtCount: number | null,
  fetchThread: () => Promise<ThreadMessage[]>,
): Promise<{ loadedAtCount: number; messages: ThreadMessage[] } | null> {
  if (!needsFetch(open, loadedAtCount, replyCount)) return null;
  const messages = await fetchThread();
  return { loadedAtCount: replyCount, messages };
}

/** The thread is "awaiting the bot's answer" when the last stored turn is a human reply the bot
 *  hasn't answered yet — a completed exchange always ends with a FollowUpGenerated (bot) turn. */
export function isAwaitingReply(previewTurns: ReviewEvent[]): boolean {
  return previewTurns.length > 0 && previewTurns[previewTurns.length - 1].type === 'AuthorReplied';
}

/**
 * A finding's conversation, collapsible. On expand it re-fetches the full thread from the SCM
 * (ADR-011 — the full text isn't persisted). The SCM messages carry no timestamps, so we pair them
 * by order with our own stored events, which do. While loading it shows a hint; on any failure it
 * falls back to the stored preview passed as {@code previewBody}, so the panel is never empty.
 *
 * The parent detail payload refreshes live, so {@code previewTurns} can grow while this panel
 * stays mounted and open (a new reply arrived) — the fetched messages are re-pulled whenever the
 * reply count moves past what was last loaded, not just on the initial expand.
 */
export function FindingConversation({
  workspace,
  slug,
  pr,
  threadRef,
  previewTurns,
  previewBody,
  resolved,
}: FindingConversationProps) {
  const [state, setState] = useState<LoadState>({ status: 'idle' });
  const [open, setOpen] = useState(false);
  const loadedAtCount = useRef<number | null>(null);
  const replyCount = previewTurns.length;
  const awaitingReply = isAwaitingReply(previewTurns);

  useEffect(() => {
    if (!needsFetch(open, loadedAtCount.current, replyCount)) return;
    let cancelled = false;
    setState({ status: 'loading' });
    refreshThread(open, replyCount, loadedAtCount.current, () => fetchThreadMessages(workspace, slug, pr, threadRef))
      .then((result) => {
        if (cancelled || !result) return;
        loadedAtCount.current = result.loadedAtCount;
        setState({ status: 'loaded', messages: result.messages });
      })
      .catch(() => {
        if (!cancelled) setState({ status: 'error' }); // leave loadedAtCount unset — retry on reopen
      });
    return () => {
      cancelled = true;
    };
    // `state`/`setState` are intentionally NOT dependencies: the effect writes state, so depending
    // on it would re-run the effect on its own setState and re-fetch in a loop. The fetch decision
    // reads only `open`, `replyCount`, and the `loadedAtCount` ref — do not let an exhaustive-deps
    // autofix add `state` here, or the stuck-loading/refetch-loop bug returns (no jsdom test guards it).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, replyCount, workspace, slug, pr, threadRef]);

  function handleToggle(e: SyntheticEvent<HTMLDetailsElement>) {
    setOpen(e.currentTarget.open);
  }

  return (
    <details className={`finding-convo${resolved ? ' convo-resolved' : ''}`} onToggle={handleToggle}>
      <summary>
        {resolved ? (
          <CheckCircle2 size={14} className="finding-convo-icon" aria-hidden="true" />
        ) : (
          <MessagesSquare size={14} className="finding-convo-icon" aria-hidden="true" />
        )}
        <span>
          {replyCount} {replyCount === 1 ? 'reply' : 'replies'}
        </span>
        {awaitingReply && (
          <span className="responding" title="Awaiting the bot's reply">
            <MessageCircle size={11} aria-hidden="true" />
            responding…
          </span>
        )}
        <ChevronDown size={14} className="finding-convo-chevron" aria-hidden="true" />
      </summary>
      {state.status === 'loaded' ? (
        <ThreadMessages messages={state.messages} previewTurns={previewTurns} />
      ) : state.status === 'loading' ? (
        <div className="convo-note">Loading full conversation…</div>
      ) : (
        previewBody
      )}
    </details>
  );
}

/** The thread's root message is the bot's inline finding comment (already shown as the finding body),
 *  so drop a leading bot message and render just the replies. */
export function conversationReplies(messages: ThreadMessage[]): ThreadMessage[] {
  return messages.length > 0 && messages[0].fromBot ? messages.slice(1) : messages;
}

/** The stored event carrying the timestamp for reply {@code i} — same order, and its kind
 *  (FollowUpGenerated = bot) must match, else we show no timestamp rather than a wrong one. */
function timestampFor(previewTurns: ReviewEvent[], i: number, fromBot: boolean): ReviewEvent | undefined {
  const turn = previewTurns[i];
  if (!turn) return undefined;
  const turnFromBot = turn.type === 'FollowUpGenerated';
  return turnFromBot === fromBot ? turn : undefined;
}

function ThreadMessages({ messages, previewTurns }: { messages: ThreadMessage[]; previewTurns: ReviewEvent[] }) {
  const replies = conversationReplies(messages);
  if (!replies.length) return <div className="convo-note">No replies in this thread.</div>;
  return (
    <div className="convo">
      {replies.map((m: ThreadMessage, i: number) => {
        const turn = timestampFor(previewTurns, i, m.fromBot);
        return (
          <div key={i} className={`convo-turn ${m.fromBot ? 'bot' : 'reply'}`}>
            <span className="convo-glyph" aria-hidden="true">
              {m.fromBot ? <Bot size={13} /> : '↩'}
            </span>
            <div className="convo-body">
              <div className="convo-who">{m.fromBot ? 'Code Spire' : m.author}</div>
              <div className="convo-det">
                <MessageText>{m.text}</MessageText>
              </div>
              {turn && (
                <div className="convo-at">
                  {formatEventTime(turn.ts)}
                  <span className="convo-at-rel">{turn.at}</span>
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
