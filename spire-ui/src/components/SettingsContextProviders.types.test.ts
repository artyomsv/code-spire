import { describe, expect, it } from 'vitest';
import { CONTEXT_TYPES, TYPE_COPY } from './SettingsContextProviders';

/**
 * A type in the selector with no copy entry renders a form with blank labels and no hint — the
 * operator gets a field they cannot interpret. The two lists must stay in step, so assert it rather
 * than trusting review to notice.
 */
describe('context provider types', () => {
  it('offers every type the backend accepts', () => {
    expect(CONTEXT_TYPES).toEqual(['jira', 'confluence', 'github-issues', 'gitlab-issues', 'code']);
  });

  it('gives every offered type its own form copy', () => {
    for (const type of CONTEXT_TYPES) {
      const copy = TYPE_COPY[type];
      expect(copy, `missing copy for ${type}`).toBeDefined();
      expect(copy.label.length).toBeGreaterThan(0);
      expect(copy.baseUrlPlaceholder.length).toBeGreaterThan(0);
      expect(copy.narrowLabel.length).toBeGreaterThan(0);
      expect(copy.previewLabel.length).toBeGreaterThan(0);
    }
  });

  /** Preview cannot resolve a bare reference, so its placeholder must not suggest one. */
  it('asks the issue types for a qualified reference or a URL', () => {
    for (const type of ['github-issues', 'gitlab-issues'] as const) {
      const placeholder = TYPE_COPY[type].previewPlaceholder(null);
      expect(placeholder).toMatch(/#123|URL/);
      expect(placeholder.startsWith('#')).toBe(false);
    }
  });

  /**
   * The backend rejects `basic` auth for the two issue providers (GitHub's basic auth is
   * deprecated; a GitLab PAT is bearer-only) — the form must not offer a choice it cannot save.
   */
  it('permits only bearer auth for the issue and code types, both kinds for Jira and Confluence', () => {
    expect(TYPE_COPY.jira.authKinds).toEqual(['basic', 'bearer']);
    expect(TYPE_COPY.confluence.authKinds).toEqual(['basic', 'bearer']);
    expect(TYPE_COPY['github-issues'].authKinds).toEqual(['bearer']);
    expect(TYPE_COPY['gitlab-issues'].authKinds).toEqual(['bearer']);
    expect(TYPE_COPY.code.authKinds).toEqual(['bearer']);
    for (const type of CONTEXT_TYPES) {
      expect(TYPE_COPY[type].authKinds.length).toBeGreaterThan(0);
    }
  });

  /**
   * `code`'s registry column is a path allow-list, not a project-key allow-list — the label and hint
   * must say so (both the words and the empty-means-unrestricted semantics), and warn that the match
   * is a prefix, not a directory boundary, since that is surprising and easy to get wrong.
   */
  it('labels the code type\'s narrow field as a path allow-list and explains the prefix match', () => {
    const copy = TYPE_COPY.code;
    expect(copy.narrowLabel).toMatch(/path/i);
    expect(copy.narrowLabel).not.toMatch(/project/i);
    expect(copy.narrowHint).toMatch(/prefix/i);
    expect(copy.narrowHint).toMatch(/trailing slash/i);
    expect(copy.narrowHint).toMatch(/optional|blank/i);
  });
});
