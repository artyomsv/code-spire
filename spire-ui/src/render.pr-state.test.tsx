import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { prStateBadge } from './render';

/**
 * The PR-state badge (open/merged/closed) — the pull/merge request's own state, distinct
 * from the review-outcome/status badge. Rendered beside it in the list row and detail header.
 */
describe('prStateBadge', () => {
  it('labels each PR state', () => {
    expect(renderToStaticMarkup(<>{prStateBadge('OPEN')}</>).toLowerCase()).toContain('open');
    expect(renderToStaticMarkup(<>{prStateBadge('MERGED')}</>).toLowerCase()).toContain('merged');
    expect(renderToStaticMarkup(<>{prStateBadge('CLOSED')}</>).toLowerCase()).toContain('closed');
  });
});
