import { describe, expect, it } from 'vitest';
import { baseUrlHint, oauthSetupGuide } from './oauthAppSetup';

/**
 * The portal instructions for an OAuth application (P4 / FR-11).
 *
 * <p>These are checked as data rather than as rendering because the risk is in the content: an
 * admin follows them inside somebody else's portal, and a step that omits a required tick box
 * produces an application that saves cleanly and then refuses every sign-in.
 */
describe('oauthSetupGuide', () => {
  it('names each platform the way the platform names itself', () => {
    expect(oauthSetupGuide('github')?.providerLabel).toBe('GitHub');
    expect(oauthSetupGuide('gitlab')?.providerLabel).toBe('GitLab');
    // Never "bitbucket-cloud": that string appears nowhere in the portal the admin is looking at.
    expect(oauthSetupGuide('bitbucket-cloud')?.providerLabel).toBe('Bitbucket');
  });

  /**
   * Two platforms will not issue a client secret unless a box is ticked, and the failure is
   * silent — the application saves, and the sign-in fails later with a message about credentials.
   */
  it('says which box makes the platform issue a secret at all', () => {
    const gitlab = oauthSetupGuide('gitlab')!.steps.map((s) => `${s.title} ${s.detail ?? ''}`);
    expect(gitlab.some((s) => s.includes('Confidential'))).toBe(true);

    const bitbucket = oauthSetupGuide('bitbucket-cloud')!.steps.map((s) => `${s.title} ${s.detail ?? ''}`);
    expect(bitbucket.some((s) => s.includes('private consumer'))).toBe(true);
  });

  /** The field is called something different on each platform, so each guide has to name its own. */
  it('names the callback field the way each platform labels it', () => {
    const titles = (type: string) => oauthSetupGuide(type)!.steps.map((s) => s.title).join(' | ');
    expect(titles('github')).toMatch(/Authorization callback URL/);
    expect(titles('gitlab')).toMatch(/Redirect URI/);
    expect(titles('bitbucket-cloud')).toMatch(/Callback URL/);
  });

  it('asks for the narrowest permission each platform offers', () => {
    const all = (type: string) =>
      oauthSetupGuide(type)!.steps.map((s) => `${s.title} ${s.detail ?? ''}`).join(' | ');
    expect(all('gitlab')).toMatch(/read_user/);
    expect(all('bitbucket-cloud')).toMatch(/Account, tick Read/);
    // GitHub needs no portal permission at all -- the scope is requested per sign-in -- and saying
    // so matters, because an admin who goes looking for a permissions section will not find one.
    expect(all('github')).toMatch(/Nothing to configure for permissions/);
  });

  /**
   * The first question anyone asks, and the one the panel originally answered nowhere: whose
   * account does this go under? It is not guessable — the application has nothing to do with the
   * bot credential, is not registered per person, and lives on an account rather than on a repo.
   */
  it('names the shared account that should own the application, per platform', () => {
    expect(oauthSetupGuide('github')!.owner.shared).toMatch(/organization/i);
    expect(oauthSetupGuide('gitlab')!.owner.shared).toMatch(/group/i);
    expect(oauthSetupGuide('bitbucket-cloud')!.owner.shared).toMatch(/workspace/i);
  });

  /**
   * Repositories owned by one person are the case this guidance is most often read in, and an
   * earlier version answered it badly — it named only the shared account and called a personal one
   * a compromise. It is not: when the repositories are personal too, the application and the
   * repositories share a fate anyway, so there is nothing for a shared account to outlive.
   */
  it('gives personal repositories an answer of their own, not a warning', () => {
    for (const type of ['github', 'gitlab', 'bitbucket-cloud']) {
      expect(oauthSetupGuide(type)!.owner.personal.length).toBeGreaterThan(0);
      expect(oauthSetupGuide(type)!.owner.personal).toMatch(/your own/i);
    }
  });

  /** The rule that decides, stated once, so neither case reads as the exception. */
  it('states the rule as matching the repositories, and names the one costly mismatch', () => {
    expect(oauthSetupGuide('github')!.owner.detail).toMatch(/right answer rather than a compromise/i);
    // The mismatch that actually costs something is the other direction.
    expect(oauthSetupGuide('github')!.owner.detail).toMatch(/leaves when that person does/i);
    // Bitbucket has no decision to make at all, and saying so beats offering a false choice.
    expect(oauthSetupGuide('bitbucket-cloud')!.owner.detail).toMatch(/same place either way/i);
  });

  /** An application lives on an account, never on a repository — a different settings page entirely. */
  it('sends the admin to the account settings, not the repository settings', () => {
    for (const type of ['github', 'gitlab', 'bitbucket-cloud']) {
      const first = oauthSetupGuide(type)!.steps[0];
      expect(`${first.title} ${first.detail ?? ''}`).toMatch(/not the (repository|project)/i);
    }
  });

  it('gives every step a non-empty title', () => {
    for (const providerType of ['github', 'gitlab', 'bitbucket-cloud']) {
      for (const step of oauthSetupGuide(providerType)!.steps) {
        expect(step.title.length).toBeGreaterThan(0);
      }
    }
  });

  it('returns null for a platform without a guide', () => {
    expect(oauthSetupGuide('bitbucket-dc')).toBeNull();
    expect(oauthSetupGuide('')).toBeNull();
  });
});

describe('baseUrlHint', () => {
  /**
   * The two fields are the same on every platform but one, and on that one they are NOT
   * interchangeable — putting the sign-in host in the API field silently identifies operators
   * against the wrong endpoint.
   */
  it('gives GitHub a different example per field', () => {
    expect(baseUrlHint('github', 'web')).toMatch(/your-github-host$/);
    expect(baseUrlHint('github', 'api')).toMatch(/api\/v3$/);
  });

  /** GitLab derives its API base by appending a path, so filling that field in is never required. */
  it('tells a self-managed GitLab operator to leave the API field alone', () => {
    expect(baseUrlHint('gitlab', 'web')).toMatch(/your-gitlab-host/);
    expect(baseUrlHint('gitlab', 'api')).toMatch(/leave empty/i);
  });

  it('says a hosted-only platform needs neither', () => {
    expect(baseUrlHint('bitbucket-cloud', 'web')).toMatch(/hosted only/);
    expect(baseUrlHint('bitbucket-cloud', 'api')).toMatch(/hosted only/);
  });

  /** An unknown platform still gets usable text rather than an empty hint. */
  it('falls back to the general rule for a platform it does not know', () => {
    expect(baseUrlHint('something-else', 'web')).toMatch(/Leave empty/);
  });
});
