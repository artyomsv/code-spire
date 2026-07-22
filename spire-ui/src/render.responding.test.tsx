import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { statusCell } from './render';

/**
 * Fix #5: while the bot is composing a follow-up reply (`answering: true`), the
 * reviews-list row and the detail header both show a subtle "responding…" indicator
 * next to the outcome badge. `statusCell` is the shared helper behind both call sites,
 * so testing it once covers list + detail.
 */
describe('statusCell — responding indicator', () => {
  it('renders a responding indicator when answering', () => {
    const html = renderToStaticMarkup(
      <>{statusCell({ status: 'completed', findings: 0, blockerCount: 0, answering: true })}</>,
    );
    expect(html.toLowerCase()).toContain('responding');
  });

  it('renders nothing extra when answering is false', () => {
    const html = renderToStaticMarkup(
      <>{statusCell({ status: 'completed', findings: 0, blockerCount: 0, answering: false })}</>,
    );
    expect(html.toLowerCase()).not.toContain('responding');
  });

  it('renders nothing extra when answering is absent', () => {
    const html = renderToStaticMarkup(
      <>{statusCell({ status: 'completed', findings: 0, blockerCount: 0 })}</>,
    );
    expect(html.toLowerCase()).not.toContain('responding');
  });
});
