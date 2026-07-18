import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { FindingConversation } from './FindingConversation';
import type { ReviewEvent } from '../api';

const turn = (): ReviewEvent => ({
  ts: '2026-07-18T00:00:00Z',
  at: '+1s',
  lane: 'integration',
  type: 'AuthorReplied',
  det: '@a: why?',
  threadRef: 't1',
});

describe('FindingConversation resolved state', () => {
  it('shows the resolved pill (check icon + convo-resolved class) when resolved', () => {
    const html = renderToStaticMarkup(
      <FindingConversation
        workspace="acme"
        slug="web"
        pr={1}
        threadRef="t1"
        previewTurns={[turn()]}
        previewBody={<div>preview</div>}
        resolved
      />,
    );
    expect(html).toContain('convo-resolved');
    expect(html).toContain('lucide-circle-check');
    expect(html).not.toContain('lucide-messages-square');
  });

  it('shows the default open-thread pill (message icon, no convo-resolved) when not resolved', () => {
    const html = renderToStaticMarkup(
      <FindingConversation
        workspace="acme"
        slug="web"
        pr={1}
        threadRef="t1"
        previewTurns={[turn()]}
        previewBody={<div>preview</div>}
      />,
    );
    expect(html).not.toContain('convo-resolved');
    expect(html).toContain('lucide-messages-square');
  });
});
