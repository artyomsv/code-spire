import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { findingsCard, generalDiscussionCard } from './render';
import type { Finding, ReviewDetail, ReviewEvent } from './api';

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
    expect(html).toContain('💬 2');
    expect(html).toContain('why?');
  });

  it('shows no panel for a finding without a thread', () => {
    const finding = { sev: 'nit', loc: 'src/App.java:1', msg: 'x' } as Finding;
    const html = renderToStaticMarkup(<>{findingsCard(detailWith([finding], []))}</>);
    expect(html).not.toContain('💬');
  });
});
