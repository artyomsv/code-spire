import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { findingsCard } from './render';
import type { ReviewDetail, ReviewStatus } from './api';

/** Minimal ReviewDetail stub — findingsCard only reads the fields it needs. */
function detail(status: ReviewStatus): ReviewDetail {
  return {
    status,
    findings: 0,
    findingsList: [],
    reconciliation: [],
    events: [],
    workspace: 'acme',
    slug: 'web',
    pr: 412,
  } as unknown as ReviewDetail;
}

describe('findingsCard — in-progress gate', () => {
  it('shows an in-progress placeholder, not "clean", while reviewing', () => {
    const html = renderToStaticMarkup(<>{findingsCard(detail('reviewing'))}</>);
    expect(html).not.toContain('No issues found');
    expect(html.toLowerCase()).toContain('analyzing');
  });

  it('shows the clean state when a completed review found nothing', () => {
    const html = renderToStaticMarkup(<>{findingsCard(detail('completed'))}</>);
    expect(html).toContain('No issues found');
  });
});
