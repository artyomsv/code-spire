import { describe, expect, it } from 'vitest';
import { openInLabel, providerBadge, providerLabel } from './render';

/**
 * The provider badge/label logic (C7): the dashboard must show the real
 * registered SCM type, taken from the stored `providerType`, and only fall back
 * to sniffing the PR URL host for legacy rows written before the type existed.
 * The self-hosted cases are the whole reason C7 exists — a GitLab/Bitbucket
 * behind a corporate host whose name contains none of the vendor strings.
 */
describe('providerLabel', () => {
  it('prefers the stored provider type over the URL', () => {
    expect(providerLabel({ providerType: 'github', htmlUrl: 'https://github.com/o/r/pull/1' })).toBe('github');
    expect(providerLabel({ providerType: 'gitlab', htmlUrl: 'https://gitlab.com/o/r/-/merge_requests/1' })).toBe(
      'gitlab',
    );
    expect(providerLabel({ providerType: 'bitbucket-cloud', htmlUrl: 'https://bitbucket.org/o/r' })).toBe('bitbucket');
    expect(providerLabel({ providerType: 'bitbucket-dc', htmlUrl: 'https://bb.corp/o/r' })).toBe('bitbucket');
  });

  it('labels self-hosted GitLab from the stored type, not the host', () => {
    // The bug C7 fixes: host contains none of "github/gitlab/bitbucket".
    expect(providerLabel({ providerType: 'gitlab', htmlUrl: 'https://git.mycorp.example/g/sub/repo/-/merge_requests/7' })).toBe(
      'gitlab',
    );
  });

  it('falls back to URL sniffing for legacy rows with no stored type', () => {
    expect(providerLabel({ providerType: '', htmlUrl: 'https://github.com/o/r/pull/1' })).toBe('github');
    expect(providerLabel({ htmlUrl: 'https://gitlab.com/o/r' })).toBe('gitlab');
    expect(providerLabel({ htmlUrl: 'https://bitbucket.org/o/r' })).toBe('bitbucket');
  });

  it('returns the em-dash sentinel when nothing identifies the provider', () => {
    expect(providerLabel({ providerType: '', htmlUrl: 'https://git.mycorp.example/g/r' })).toBe('—');
    expect(providerLabel({})).toBe('—');
    expect(providerLabel(undefined)).toBe('—');
  });
});

describe('providerBadge', () => {
  it('renders a brand-classed badge from the stored type', () => {
    const badge = providerBadge({ providerType: 'gitlab', htmlUrl: 'https://git.mycorp.example/g/r' });
    expect(badge).not.toBeNull();
    expect(badge?.props.className).toBe('prov-badge prov-gitlab');
    expect(badge?.props.children).toBe('gitlab');
  });

  it('is null (no badge) when the provider is unknown', () => {
    expect(providerBadge({ providerType: '', htmlUrl: 'https://git.mycorp.example/g/r' })).toBeNull();
  });
});

describe('openInLabel', () => {
  it('names the real provider, including self-hosted GitLab', () => {
    expect(openInLabel({ providerType: 'gitlab', htmlUrl: 'https://git.mycorp.example/g/r' })).toBe('Open in GitLab');
    expect(openInLabel({ providerType: 'github', htmlUrl: 'https://github.com/o/r' })).toBe('Open in GitHub');
    expect(openInLabel({ providerType: 'bitbucket-dc', htmlUrl: 'https://bb.corp/o/r' })).toBe('Open in Bitbucket');
  });

  it('falls back to a generic label when unknown', () => {
    expect(openInLabel({ providerType: '', htmlUrl: 'https://git.mycorp.example/g/r' })).toBe('Open in provider');
  });
});
