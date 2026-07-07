import { describe, expect, it } from 'vitest';
import { safeHttpUrl, STAGES, stageLabel } from './render';

/**
 * Server-provided URLs (htmlUrl) land in <a href> — React does not block
 * `javascript:`/`data:` schemes, so everything must pass through safeHttpUrl.
 */
describe('safeHttpUrl', () => {
  it('passes http and https URLs through unchanged', () => {
    expect(safeHttpUrl('https://github.com/o/r/pull/1')).toBe('https://github.com/o/r/pull/1');
    expect(safeHttpUrl('http://bb.corp:7990/projects/X/repos/y/pull-requests/2')).toBe(
      'http://bb.corp:7990/projects/X/repos/y/pull-requests/2',
    );
  });

  it('rejects javascript: and data: schemes', () => {
    expect(safeHttpUrl('javascript:alert(1)')).toBeUndefined();
    expect(safeHttpUrl('JavaScript:alert(1)')).toBeUndefined();
    expect(safeHttpUrl('data:text/html,<script>alert(1)</script>')).toBeUndefined();
    expect(safeHttpUrl('vbscript:msgbox')).toBeUndefined();
    expect(safeHttpUrl('file:///etc/passwd')).toBeUndefined();
  });

  it('rejects empty, missing and unparsable values', () => {
    expect(safeHttpUrl(undefined)).toBeUndefined();
    expect(safeHttpUrl('')).toBeUndefined();
    expect(safeHttpUrl('not a url')).toBeUndefined();
    expect(safeHttpUrl('/relative/path')).toBeUndefined();
  });
});

describe('stageLabel', () => {
  it('labels in-range stages directly', () => {
    expect(stageLabel(0)).toBe('Received');
    expect(stageLabel(5)).toBe('Done');
  });

  it('clamps stage 6 (past Done) to the last label instead of rendering empty', () => {
    expect(stageLabel(6)).toBe('Done');
    expect(stageLabel(STAGES.length + 3)).toBe('Done');
  });
});
