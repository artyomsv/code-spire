import { describe, expect, it } from 'vitest';
import { accountKind, accountOptionLabel, hostOf, roleLabel } from './accounts';

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

describe('accountOptionLabel', () => {
  // The discriminating case, not the obvious one: after V59 the same NAME legitimately exists
  // three times on one host, so a label that drops kind or role makes them indistinguishable.
  it('separates same-named accounts by kind and role', () => {
    const reviewer = accountOptionLabel({ name: 'public-github', type: 'github', role: 'REVIEWER', enabled: true });
    const factory = accountOptionLabel({ name: 'public-github', type: 'github', role: 'FACTORY', enabled: true });
    const context = accountOptionLabel({ name: 'public-github', type: 'github', role: 'CONTEXT', enabled: true });
    expect(new Set([reviewer, factory, context]).size).toBe(3);
    expect(reviewer).toBe('public-github · github · Reviewer');
  });

  it('marks a disabled account without hiding its kind or role', () => {
    expect(accountOptionLabel({ name: 'jira', type: 'atlassian', role: 'CONTEXT', enabled: false }))
      .toBe('jira · atlassian · Context (disabled)');
  });
});
