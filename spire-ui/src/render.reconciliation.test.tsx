import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { reconciliationCard } from './render';
import type { ReconciliationItem } from './api';

/**
 * Task 10's reconciliation array reuses the findings' display severity slugs
 * ('critical' | 'warning' | 'suggestion' | 'nit'), not enum names — the brief's
 * sample data used 'MAJOR'/'MINOR', adjusted here to match the real contract.
 */
const items: ReconciliationItem[] = [
  {
    sev: 'critical',
    loc: 'src/A.java:7',
    msg: 'leak',
    status: 'resolved',
    note: 'fixed',
    threadRef: 't1',
    resolvedThread: true,
  },
  {
    sev: 'warning',
    loc: 'src/B.java:2',
    msg: 'naming',
    status: 'still open',
    note: 'rename missing',
    threadRef: 't2',
    resolvedThread: false,
  },
];

describe('reconciliationCard', () => {
  it('shows the banner counts and one row per verdict', () => {
    const html = renderToStaticMarkup(<>{reconciliationCard(items)}</>);
    expect(html).toContain('1 closed');
    expect(html).toContain('1 still open');
    expect(html).toContain('src/A.java:7');
    expect(html).toContain('rename missing');
  });

  it('renders nothing when there are no verdicts', () => {
    expect(reconciliationCard([])).toBeNull();
  });

  it('counts acknowledged/superseded as closed alongside resolved', () => {
    const html = renderToStaticMarkup(
      <>
        {reconciliationCard([
          { sev: 'nit', loc: 'x:1', msg: 'm', status: 'acknowledged' },
          { sev: 'nit', loc: 'y:1', msg: 'm', status: 'superseded' },
          { sev: 'nit', loc: 'z:1', msg: 'm', status: 'still open' },
        ])}
      </>,
    );
    expect(html).toContain('2 closed');
    expect(html).toContain('1 still open');
  });
});
