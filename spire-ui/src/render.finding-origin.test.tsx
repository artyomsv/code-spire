import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { findingsCard } from './render';
import type { Finding, ReconciliationItem, ReviewDetail } from './api';

/** Minimal ReviewDetail stub — findingsCard only reads the fields it needs. The two list
 *  parameters are typed, so an `origin` a caller invents is still a compile error. */
function detail(findingsList: Finding[], reconciliation: ReconciliationItem[] = []): ReviewDetail {
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

describe('findingsCard — finding origin', () => {
  it('marks a finding a human filed from a discussion', () => {
    const html = renderToStaticMarkup(<>{findingsCard(detail([
      { sev: 'suggestion', loc: 'src/Foo.java:44', msg: 'shadows the field', origin: 'conversation' },
    ]))}</>);

    expect(html).toMatch(/from discussion/i);
    // Guards only the render side: that findingsCard still emits the "provenance" modifier class,
    // like every other .pill.*. It does NOT prove index.css still defines a matching rule — a rename
    // over there would leave this assertion (rendered markup only) green while the badge silently
    // lost its styling.
    expect(html).toContain('pill provenance');
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

  /**
   * The round after it is filed, a conversation finding is no longer a `findingsList` entry — it is
   * a reconciliation verdict. A badge only the fresh-finding row can render therefore lasts exactly
   * one round, which is most of the life of the defect this closes.
   */
  it('keeps the mark on a filed finding once it is a reconciliation verdict', () => {
    const html = renderToStaticMarkup(<>{findingsCard(detail([], [
      {
        sev: 'suggestion',
        loc: 'src/Foo.java:44',
        msg: 'shadows the field',
        status: 'still open',
        threadRef: 't-900',
        origin: 'conversation',
      },
    ]))}</>);

    expect(html).toMatch(/from discussion/i);
  });

  it('leaves a verdict on a review-derived finding unmarked', () => {
    const html = renderToStaticMarkup(<>{findingsCard(detail([], [
      { sev: 'warning', loc: 'src/Bar.java:10', msg: 'leaks a handle', status: 'still open', threadRef: 't-1' },
    ]))}</>);

    expect(html).not.toMatch(/from discussion/i);
  });
});
