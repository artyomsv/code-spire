import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { findingsCard } from './render';
import type { Finding, ReviewDetail } from './api';

/** Minimal ReviewDetail stub — findingsCard only reads the fields it needs. */
function detail(findingsList: Finding[]): ReviewDetail {
  return {
    status: 'completed',
    findings: findingsList.length,
    findingsList,
    reconciliation: [],
    events: [],
    workspace: 'acme',
    slug: 'web',
    pr: 412,
  } as unknown as ReviewDetail;
}

describe('findingsCard — finding origin', () => {
  it('marks a finding a human filed from a discussion', () => {
    const html = renderToStaticMarkup(<>{findingsCard(detail([
      { sev: 'suggestion', loc: 'src/Foo.java:44', msg: 'shadows the field', origin: 'conversation' },
    ]))}</>);

    expect(html).toMatch(/from discussion/i);
  });

  it('leaves a review-derived finding unmarked', () => {
    const html = renderToStaticMarkup(<>{findingsCard(detail([
      { sev: 'warning', loc: 'src/Bar.java:10', msg: 'leaks a handle' },
    ]))}</>);

    expect(html).not.toMatch(/from discussion/i);
  });

  it('treats a stored row with no origin as review-derived', () => {
    // Every row written before this field existed deserializes with origin undefined. Rendering
    // those as "from discussion" would attribute the model's findings to people.
    const html = renderToStaticMarkup(<>{findingsCard(detail([
      { sev: 'warning', loc: 'src/Bar.java:10', msg: 'leaks a handle', origin: undefined },
    ]))}</>);

    expect(html).not.toMatch(/from discussion/i);
  });
});
