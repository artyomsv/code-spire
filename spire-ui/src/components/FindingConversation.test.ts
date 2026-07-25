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

  it('keeps only the stored replies when the opener is shown above (Bitbucket summary thread)', () => {
    // Bitbucket returns [summary comment, bot answer] — one stored reply, so just the answer.
    const replies = conversationReplies([bot('### Code Spire review'), bot('risk is high')], true, 1);
    expect(replies.map((m) => m.text)).toEqual(['risk is high']);
  });

  it('does not repeat the opener when the provider returns it inside the transcript (GitHub)', () => {
    // GitHub returns [summary comment, the question, bot answer]. Dropping a fixed leading message
    // left the question in the panel while it was also rendered above it.
    const replies = conversationReplies(
      [bot('### Code Spire review'), human('overall risk?'), bot('risk is high')], true, 1);
    expect(replies.map((m) => m.text)).toEqual(['risk is high']);
  });

  it('keeps every stored turn of a longer general thread in order', () => {
    const replies = conversationReplies(
      [bot('summary'), human('q1'), bot('a1'), human('q2'), bot('a2')], true, 3);
    expect(replies.map((m) => m.text)).toEqual(['a1', 'q2', 'a2']);
  });

  it('shows what follows the opener even when a thread holds an unanswered reply', () => {
    // The thread the bot joined by @-mention: question, answer, then a second question the policy
    // declined (so no stored turn for it). Counting back one message showed that unanswered question and
    // HID the bot's answer — the whole point of expanding the panel.
    const replies = conversationReplies(
      [human('@code-spire-bot can you simplify that?'), bot('Line 15 can be shortened'),
        human('are there any alternative code that can do this?')],
      true, 1, '@artyomsv: @code-spire-bot can you simplify that?');
    expect(replies.map((m) => m.text)).toEqual([
      'Line 15 can be shortened', 'are there any alternative code that can do this?',
    ]);
  });

  it('matches an opener whose preview was truncated', () => {
    const long = 'a'.repeat(200);
    const replies = conversationReplies([human(long), bot('answer')], true, 1,
      '@artyomsv: ' + 'a'.repeat(160) + '…');
    expect(replies.map((m) => m.text)).toEqual(['answer']);
  });

  it('matches an opener whose stored preview collapsed its newlines', () => {
    const replies = conversationReplies([human('first line\n\nsecond line'), bot('answer')], true, 1,
      '@artyomsv: first line second line');
    expect(replies.map((m) => m.text)).toEqual(['answer']);
  });

  it('falls back to the trailing count when the opener cannot be matched', () => {
    const replies = conversationReplies([human('unrelated'), bot('answer')], true, 1, '@a: nothing alike');
    expect(replies.map((m) => m.text)).toEqual(['answer']);
  });

  it('falls back to the whole transcript when it is no longer than the stored replies', () => {
    const replies = conversationReplies([human('q1'), bot('a1')], true, 3);
    expect(replies.map((m) => m.text)).toEqual(['q1', 'a1']);
  });

  it('is safe on an empty transcript', () => {
    expect(conversationReplies([], true, 2)).toEqual([]);
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
