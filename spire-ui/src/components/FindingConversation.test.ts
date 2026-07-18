import { describe, expect, it } from 'vitest';
import { conversationReplies } from './FindingConversation';
import type { ThreadMessage } from '../api';

const bot = (text: string): ThreadMessage => ({ author: 'code-spire-bot', text, fromBot: true });
const human = (text: string): ThreadMessage => ({ author: 'artyomsv', text, fromBot: false });

describe('conversationReplies', () => {
  it('drops the leading bot root (the finding comment) and keeps the replies', () => {
    const replies = conversationReplies([bot('finding'), human('why?'), bot('because')]);
    expect(replies.map((m) => m.text)).toEqual(['why?', 'because']);
  });

  it('keeps all messages when the first is not from the bot', () => {
    const replies = conversationReplies([human('a'), bot('b')]);
    expect(replies.map((m) => m.text)).toEqual(['a', 'b']);
  });

  it('handles an empty thread', () => {
    expect(conversationReplies([])).toEqual([]);
  });
});
