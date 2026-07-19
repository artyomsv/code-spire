import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { findingsCard } from './render';
import type { Finding, ReconciliationItem, ReviewDetail } from './api';

/** Minimal ReviewDetail stub — findingsCard only reads the fields it needs. */
function detail(findingsList: Finding[], reconciliation: ReconciliationItem[]): ReviewDetail {
  return {
    status: 'completed',
    findings: findingsList.length,
    findingsList,
    reconciliation,
    events: [],
    workspace: 'acme',
    slug: 'web',
    pr: 412,
  } as unknown as ReviewDetail;
}

describe('findingsCard — unified findings + reconciliation', () => {
  it('shows one open group (new + still-open + unchanged) and a collapsed resolved group', () => {
    const findingsList: Finding[] = [{ sev: 'critical', loc: 'src/A.java:1', msg: 'new issue' }];
    const reconciliation: ReconciliationItem[] = [
      { sev: 'warning', loc: 'src/B.java:2', msg: 'still open issue', status: 'still open' },
      { sev: 'nit', loc: 'src/C.java:3', msg: 'unchanged issue', status: 'unchanged' },
      { sev: 'suggestion', loc: 'src/D.java:4', msg: 'resolved issue', status: 'resolved' },
      { sev: 'nit', loc: 'src/E.java:5', msg: 'acknowledged issue', status: 'acknowledged' },
    ];
    const html = renderToStaticMarkup(<>{findingsCard(detail(findingsList, reconciliation))}</>);

    expect(html).toContain('3 open');
    expect(html).toContain('2 closed');
    expect(html).toContain('Resolved (2)');

    // open rows appear before the resolved group in document order
    const resolvedGroupIdx = html.indexOf('Resolved (2)');
    expect(html.indexOf('new issue')).toBeLessThan(resolvedGroupIdx);
    expect(html.indexOf('still open issue')).toBeLessThan(resolvedGroupIdx);
    expect(html.indexOf('unchanged issue')).toBeLessThan(resolvedGroupIdx);

    // resolved rows are inside the resolved group
    expect(html.indexOf('resolved issue')).toBeGreaterThan(resolvedGroupIdx);
    expect(html.indexOf('acknowledged issue')).toBeGreaterThan(resolvedGroupIdx);
  });

  it('renders like today when reconciliation is empty', () => {
    const findingsList: Finding[] = [
      { sev: 'critical', loc: 'src/A.java:1', msg: 'only finding' },
    ];
    const html = renderToStaticMarkup(<>{findingsCard(detail(findingsList, []))}</>);

    expect(html).toContain('1 open');
    expect(html).toContain('0 closed');
    expect(html).not.toContain('Resolved (');
    expect(html).toContain('only finding');
  });

  it('preserves the existing empty state when there is nothing at all', () => {
    const html = renderToStaticMarkup(<>{findingsCard(detail([], []))}</>);
    expect(html).toContain('clean');
    expect(html).toContain('No issues found in this diff.');
    expect(html).not.toContain('open');
    expect(html).not.toContain('Resolved (');
  });

  it('prefers the findingsList copy when the same thread appears in both sets', () => {
    const findingsList: Finding[] = [
      { sev: 'critical', loc: 'src/A.java:9', msg: 'no compile', threadRef: 'c1' },
    ];
    const reconciliation: ReconciliationItem[] = [
      { sev: 'critical', loc: 'src/A.java:9', msg: 'no compile', status: 'resolved', threadRef: 'c1', resolvedThread: true },
    ];
    const html = renderToStaticMarkup(<>{findingsCard(detail(findingsList, reconciliation))}</>);

    // only one row for this thread — not duplicated in both the open list and the resolved group
    expect(html).toContain('1 open');
    expect(html).toContain('0 closed');
    expect(html).not.toContain('Resolved (');
  });
});
