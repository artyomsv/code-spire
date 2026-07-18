import { useState, type ReactNode, type SyntheticEvent } from 'react';
import { Bot, ChevronDown, MessagesSquare } from 'lucide-react';
import { fetchThreadMessages, type ThreadMessage } from '../api';

interface FindingConversationProps {
  workspace: string;
  slug: string;
  pr: number;
  threadRef: string;
  replyCount: number; // known from the stored events — labels the toggle without a fetch
  previewBody: ReactNode; // the ≤160-char preview exchanges, shown until (or unless) the full thread loads
}

type LoadState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'loaded'; messages: ThreadMessage[] }
  | { status: 'error' };

/**
 * A finding's conversation, collapsible. On expand it re-fetches the full thread from the SCM
 * (ADR-011 — the full text isn't persisted). While loading it shows a hint; on any failure it falls
 * back to the stored preview passed in as {@code previewBody}, so the panel is never empty.
 */
export function FindingConversation({
  workspace,
  slug,
  pr,
  threadRef,
  replyCount,
  previewBody,
}: FindingConversationProps) {
  const [state, setState] = useState<LoadState>({ status: 'idle' });

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
    <details className="finding-convo" onToggle={handleToggle}>
      <summary>
        <MessagesSquare size={14} className="finding-convo-icon" aria-hidden="true" />
        <span>
          {replyCount} {replyCount === 1 ? 'reply' : 'replies'}
        </span>
        <ChevronDown size={14} className="finding-convo-chevron" aria-hidden="true" />
      </summary>
      {state.status === 'loaded' ? (
        <ThreadMessages messages={state.messages} />
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

function ThreadMessages({ messages }: { messages: ThreadMessage[] }) {
  const replies = conversationReplies(messages);
  if (!replies.length) return <div className="convo-note">No replies in this thread.</div>;
  return (
    <div className="convo">
      {replies.map((m: ThreadMessage, i: number) => (
        <div key={i} className={`convo-turn ${m.fromBot ? 'bot' : 'reply'}`}>
          <span className="convo-glyph" aria-hidden="true">
            {m.fromBot ? <Bot size={13} /> : '↩'}
          </span>
          <div className="convo-body">
            <div className="convo-who">{m.fromBot ? 'Code Spire' : m.author}</div>
            <div className="convo-det">{m.text}</div>
          </div>
        </div>
      ))}
    </div>
  );
}
