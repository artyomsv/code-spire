import { describe, expect, it } from 'vitest';
import { accountKind, hostOf, roleLabel } from './accounts';

describe('roleLabel', () => {
  it('names the two roles the server knows', () => {
    expect(roleLabel('REVIEWER')).toBe('Reviewer');
    expect(roleLabel('FACTORY')).toBe('Factory');
  });

  /** A role arrives as JSON. A value the union does not list must not fall into either real label. */
  it('marks anything else as unknown, never as a real role', () => {
    expect(roleLabel('OVERLORD')).toBe('Unknown (OVERLORD)');
    expect(roleLabel(null)).toBe('Unknown ()');
    expect(roleLabel(undefined)).toBe('Unknown ()');
  });
});

describe('accountKind', () => {
  it('calls the code index knowledge and every tracker a tracker', () => {
    expect(accountKind('code')).toBe('Knowledge');
    expect(accountKind('jira')).toBe('Tracker');
    expect(accountKind('confluence')).toBe('Tracker');
    expect(accountKind('github-issues')).toBe('Tracker');
    expect(accountKind('gitlab-issues')).toBe('Tracker');
  });
});

describe('hostOf', () => {
  it('shows the host of a URL', () => {
    expect(hostOf('https://acme.atlassian.net/wiki')).toBe('acme.atlassian.net');
  });

  it('falls back to the raw value when it is not a URL', () => {
    expect(hostOf('not a url')).toBe('not a url');
  });
});
