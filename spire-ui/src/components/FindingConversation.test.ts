import { describe, expect, it, vi } from 'vitest';
import { conversationReplies, isAwaitingReply, needsFetch, refreshThread } from './FindingConversation';
import type { ReviewEvent, ThreadMessage } from '../api';

const bot = (text: string): ThreadMessage => ({ author: 'code-spire-bot', text, fromBot: true });
const human = (text: string): ThreadMessage => ({ author: 'artyomsv', text, fromBot: false });

const turn = (type: string): ReviewEvent => ({
  ts: '2026-07-18T00:00:00Z',
  at: '+1s',
  lane: 'integration',
  type,
  det: '',
  threadRef: 't1',
});

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

describe('needsFetch', () => {
  it('is false while closed, regardless of load state', () => {
    expect(needsFetch(false, null, 2)).toBe(false);
    expect(needsFetch(false, 2, 2)).toBe(false);
  });

  it('is true on first expand (nothing loaded yet)', () => {
    expect(needsFetch(true, null, 2)).toBe(true);
  });

  it('is false once loaded at the current reply count', () => {
    expect(needsFetch(true, 2, 2)).toBe(false);
  });

  it('is true again once the reply count grows past what was loaded', () => {
    expect(needsFetch(true, 2, 3)).toBe(true);
  });
});

describe('refreshThread', () => {
  it('does not call the fetcher while closed', async () => {
    const fetchThread = vi.fn();
    const result = await refreshThread(false, 2, null, fetchThread);
    expect(fetchThread).not.toHaveBeenCalled();
    expect(result).toBeNull();
  });

  it('fetches once on first expand and reports the loaded count', async () => {
    const messages = [human('why?')];
    const fetchThread = vi.fn().mockResolvedValue(messages);
    const result = await refreshThread(true, 2, null, fetchThread);
    expect(fetchThread).toHaveBeenCalledTimes(1);
    expect(result).toEqual({ loadedAtCount: 2, messages });
  });

  it('does not re-fetch while open if already loaded at the current reply count', async () => {
    const fetchThread = vi.fn();
    const result = await refreshThread(true, 2, 2, fetchThread);
    expect(fetchThread).not.toHaveBeenCalled();
    expect(result).toBeNull();
  });

  it('re-fetches a SECOND time once new turns arrive while the thread stays open', async () => {
    const fetchThread = vi.fn().mockResolvedValue([]);
    const first = await refreshThread(true, 2, null, fetchThread);
    const second = await refreshThread(true, 3, first?.loadedAtCount ?? null, fetchThread);
    expect(fetchThread).toHaveBeenCalledTimes(2);
    expect(second).toEqual({ loadedAtCount: 3, messages: [] });
  });
});

describe('isAwaitingReply', () => {
  it('is false with no turns', () => {
    expect(isAwaitingReply([])).toBe(false);
  });

  it('is true when the last turn is a human reply the bot has not answered', () => {
    expect(isAwaitingReply([turn('FollowUpGenerated'), turn('AuthorReplied')])).toBe(true);
  });

  it('is false when the exchange ends with the bot answering', () => {
    expect(isAwaitingReply([turn('AuthorReplied'), turn('FollowUpGenerated')])).toBe(false);
  });
});
