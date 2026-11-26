import { render, screen } from '@testing-library/react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { findingsCard, generalDiscussionCard } from './render';
import type { Finding, ReviewDetail, ReviewEvent } from './api';

/**
 * A reply the bot chose not to answer (`FollowUpDeclined`, PR #457) ends the conversation's pending state: no
 * "responding…" mark, and a quiet "Not answered: <why>" note instead. It is not a message, so the reply count does not
 * include it. A later reply makes the thread pending again.
 */
const ev = (type: string, det: string, threadRef: string, threadKind: ReviewEvent['threadKind']): ReviewEvent => ({
  ts: '2026-07-18T00:00:00Z', at: '+1s', lane: 'integration', type, det, threadRef, threadKind,
});

const DECLINED = 'reply not answered: another person is in the thread and the reply did not mention the bot';
const NOTE = 'Not answered: another person is in the thread and the reply did not mention the bot';

function findingThread(events: ReviewEvent[]): string {
  const finding = { sev: 'warning', loc: 'src/App.java:9', msg: 'TEST-finding', threadRef: 'c1' } as Finding;
  const r = { status: 'completed', findings: 1, findingsList: [finding], events } as unknown as ReviewDetail;
  return renderToStaticMarkup(<>{findingsCard(r)}</>);
}

function generalThread(events: ReviewEvent[]): string {
  return renderToStaticMarkup(<>{generalDiscussionCard({ events, findingsList: [] } as unknown as ReviewDetail)}</>);
}

describe("a finding's conversation", () => {
  it('ends the pending reply at a decline and says why', () => {
    const html = findingThread([
      ev('AuthorReplied', '@a: why?', 'c1', 'finding'),
      ev('FollowUpDeclined', DECLINED, 'c1', 'finding'),
    ]);

    expect(html).not.toContain('responding…');
    expect(html).toContain(NOTE);
    expect(html).toContain('1 reply');
  });

  it('is pending again after a later reply, and the note goes', () => {
    const html = findingThread([
      ev('AuthorReplied', '@a: why?', 'c1', 'finding'),
      ev('FollowUpDeclined', DECLINED, 'c1', 'finding'),
      ev('AuthorReplied', '@a: @bot why?', 'c1', 'finding'),
    ]);

    expect(html).toContain('responding…');
    expect(html).not.toContain('Not answered');
    expect(html).toContain('2 replies');
  });

  /** The first reply's decline lands after a second reply was asked: the second is still being answered. */
  it('stays pending when a decline of an earlier reply lands after a later one', () => {
    const html = findingThread([
      ev('AuthorReplied', '@a: why?', 'c1', 'finding'),
      ev('AuthorReplied', '@b: @bot why?', 'c1', 'finding'),
      ev('FollowUpDeclined', DECLINED, 'c1', 'finding'),
    ]);

    expect(html).toContain('responding…');
    expect(html).not.toContain('Not answered');
  });
});

describe('a general discussion thread', () => {
  it('ends the pending reply at a decline and says why', () => {
    const html = generalThread([
      ev('AuthorReplied', '@a: overall?', 'm1', 'mention'),
      ev('FollowUpGenerated', 'It is fine.', 'm1', 'mention'),
      ev('AuthorReplied', '@b: and this?', 'm1', 'mention'),
      ev('FollowUpDeclined', DECLINED, 'm1', 'mention'),
    ]);

    expect(html).not.toContain('responding…');
    expect(html).toContain(NOTE);
    expect(html).toContain('2 replies');
  });

  it('says why when the declined reply is the opener itself', () => {
    const html = generalThread([
      ev('AuthorReplied', '@a: overall?', 'm1', 'mention'),
      ev('FollowUpDeclined', DECLINED, 'm1', 'mention'),
    ]);

    expect(html).toContain('overall?');
    expect(html).toContain(NOTE);
    expect(html).not.toContain('finding-convo');
  });

  it('is pending again after a later reply', () => {
    const html = generalThread([
      ev('AuthorReplied', '@a: overall?', 'm1', 'mention'),
      ev('FollowUpDeclined', DECLINED, 'm1', 'mention'),
      ev('AuthorReplied', '@a: @bot overall?', 'm1', 'mention'),
    ]);

    expect(html).toContain('responding…');
    expect(html).not.toContain('Not answered');
  });
});

/**
 * The conversation panel starts closed, and static markup emits a closed panel's content as if it were shown. So the
 * note's visibility is asserted on a real DOM: it must be readable without opening the panel (PR #457).
 */
describe('the decline note while the conversation panel is closed', () => {
  it('is visible under a finding', () => {
    const finding = { sev: 'warning', loc: 'src/App.java:9', msg: 'TEST-finding', threadRef: 'c1' } as Finding;
    const events = [ev('AuthorReplied', '@a: why?', 'c1', 'finding'), ev('FollowUpDeclined', DECLINED, 'c1', 'finding')];
    render(<>{findingsCard({ status: 'completed', findings: 1, findingsList: [finding], events } as unknown as ReviewDetail)}</>);

    expect(screen.getByText('1 reply').closest('details')).not.toHaveAttribute('open');
    expect(screen.getByText(NOTE)).toBeVisible();
  });

  it('is visible under a general discussion thread with replies', () => {
    const events = [
      ev('AuthorReplied', '@a: overall?', 'm1', 'mention'),
      ev('FollowUpGenerated', 'It is fine.', 'm1', 'mention'),
      ev('AuthorReplied', '@b: and this?', 'm1', 'mention'),
      ev('FollowUpDeclined', DECLINED, 'm1', 'mention'),
    ];
    render(<>{generalDiscussionCard({ events, findingsList: [] } as unknown as ReviewDetail)}</>);

    expect(screen.getByText('2 replies').closest('details')).not.toHaveAttribute('open');
    expect(screen.getByText(NOTE)).toBeVisible();
  });
});
