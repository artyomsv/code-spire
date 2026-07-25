import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { findingsCard, generalDiscussionCard } from './render';
import type { Finding, ReconciliationItem, ReviewDetail, ReviewEvent } from './api';

function detail(events: ReviewEvent[]): ReviewDetail {
  return { events, findingsList: [] } as unknown as ReviewDetail;
}

const ev = (type: string, det: string, threadKind?: ReviewEvent['threadKind']): ReviewEvent => ({
  ts: '2026-07-18T00:00:00Z', at: '+1s', lane: 'integration', type, det, threadKind,
});

describe('generalDiscussionCard', () => {
  it('renders only non-finding turns', () => {
    const html = renderToStaticMarkup(
      <>{generalDiscussionCard(detail([
        ev('AuthorReplied', '@a: on line 9', 'finding'),   // excluded
        ev('AuthorReplied', '@a: overall?', 'summary'),    // included
      ]))}</>,
    );
    expect(html).toContain('General discussion');
    expect(html).toContain('overall?');
    expect(html).not.toContain('on line 9');
  });

  it('renders nothing when there are only finding turns', () => {
    expect(generalDiscussionCard(detail([ev('AuthorReplied', '@a: x', 'finding')]))).toBeNull();
  });

  it('wraps a threaded conversation so it can re-fetch the full untruncated text', () => {
    // The stored event detail is only a ≤160-char preview, so a threaded general conversation must
    // get the same collapsible that re-fetches the full thread from the SCM as a finding's does.
    const html = renderToStaticMarkup(
      <>{generalDiscussionCard(detail([
        { ...ev('AuthorReplied', '@a: is this line ok?', 'mention'), threadRef: 'm1' },
        { ...ev('FollowUpGenerated', 'It is fine because …', 'mention'), threadRef: 'm1' },
      ]))}</>,
    );
    expect(html).toContain('finding-convo'); // the fetch-on-expand panel
    expect(html).toContain('2 replies');
    expect(html).toContain('is this line ok?'); // preview until expanded
  });

  it('groups turns per thread so separate conversations stay separate', () => {
    const html = renderToStaticMarkup(
      <>{generalDiscussionCard(detail([
        { ...ev('AuthorReplied', '@a: first thread', 'mention'), threadRef: 'm1' },
        { ...ev('AuthorReplied', '@a: second thread', 'mention'), threadRef: 'm2' },
      ]))}</>,
    );
    expect(html.match(/class="finding-convo"/g)?.length).toBe(2); // one panel per thread
    expect(html).toContain('first thread');
    expect(html).toContain('second thread');
  });
});

function detailWith(findingsList: Finding[], events: ReviewEvent[]): ReviewDetail {
  return { status: 'completed', findings: findingsList.length, findingsList, events } as unknown as ReviewDetail;
}

describe('findingsCard nested conversation', () => {
  it('nests a finding thread and shows a turn-count badge', () => {
    const finding = { sev: 'critical', loc: 'src/App.java:9', msg: 'no compile', threadRef: 'c1' } as Finding;
    const html = renderToStaticMarkup(<>{findingsCard(detailWith([finding], [
      { ...ev('AuthorReplied', '@a: why?', 'finding'), threadRef: 'c1' },
      { ...ev('FollowUpGenerated', 'Because …', 'finding'), threadRef: 'c1' },
    ]))}</>);
    expect(html).toContain('2 replies');
    expect(html).toContain('finding-convo');
    expect(html).toContain('why?');
  });

  it('shows no panel for a finding without a thread', () => {
    const finding = { sev: 'nit', loc: 'src/App.java:1', msg: 'x' } as Finding;
    const html = renderToStaticMarkup(<>{findingsCard(detailWith([finding], []))}</>);
    expect(html).not.toContain('finding-convo');
  });

  it('marks the conversation resolved when the reconciliation array says so', () => {
    const finding = { sev: 'critical', loc: 'src/App.java:9', msg: 'no compile', threadRef: 'c1' } as Finding;
    const reconciliation: ReconciliationItem[] = [
      { sev: 'critical', loc: 'src/App.java:9', msg: 'no compile', status: 'resolved', threadRef: 'c1', resolvedThread: true },
    ];
    const events = [
      { ...ev('AuthorReplied', '@a: why?', 'finding'), threadRef: 'c1' },
      { ...ev('FollowUpGenerated', 'Because …', 'finding'), threadRef: 'c1' },
    ];
    const detail = { ...detailWith([finding], events), reconciliation } as ReviewDetail;
    const html = renderToStaticMarkup(<>{findingsCard(detail)}</>);
    expect(html).toContain('convo-resolved');
  });

  it('leaves the conversation unresolved when the thread has no matching verdict', () => {
    const finding = { sev: 'critical', loc: 'src/App.java:9', msg: 'no compile', threadRef: 'c1' } as Finding;
    const events = [
      { ...ev('AuthorReplied', '@a: why?', 'finding'), threadRef: 'c1' },
      { ...ev('FollowUpGenerated', 'Because …', 'finding'), threadRef: 'c1' },
    ];
    const html = renderToStaticMarkup(<>{findingsCard(detailWith([finding], events))}</>);
    expect(html).not.toContain('convo-resolved');
  });
});
