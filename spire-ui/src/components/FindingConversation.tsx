import { useState, type ReactNode, type SyntheticEvent } from 'react';
import { Bot, CheckCircle2, ChevronDown, MessagesSquare } from 'lucide-react';
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

/**
 * A finding's conversation, collapsible. On expand it re-fetches the full thread from the SCM
 * (ADR-011 — the full text isn't persisted). The SCM messages carry no timestamps, so we pair them
 * by order with our own stored events, which do. While loading it shows a hint; on any failure it
 * falls back to the stored preview passed as {@code previewBody}, so the panel is never empty.
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
  const replyCount = previewTurns.length;

  async function handleToggle(e: SyntheticEvent<HTMLDetailsElement>) {
    if (!e.currentTarget.open) return; // only fetch on expand
    if (state.status === 'loading' || state.status === 'loaded') return; // fetch once (retry on reopen after error)
    setState({ status: 'loading' });
    try {
      const messages = await fetchThreadMessages(workspace, slug, pr, threadRef);
      setState({ status: 'loaded', messages });
    } catch {
      setState({ status: 'error' });
    }
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
