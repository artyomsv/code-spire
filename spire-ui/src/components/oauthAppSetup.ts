// Per-platform "what to do on the portal" steps for the OAuth application an operator signs into
// (Settings → Operators → Sign-in applications). The same shape as `webhookSetup.ts`, and for the
// same reason: the product hands the admin a value to paste into somebody else's portal, and every
// mistake there fails on the platform's side with a message that names nothing in this product.
//
// The steps reference "above" for the redirect address, which the panel renders with a Copy button.
// Kept framework-free so it unit-tests without rendering.

export interface OAuthSetupStep {
  /** Short imperative — the action for this step. */
  title: string;
  /** Optional secondary line (a field name, a constraint, a note about self-hosting). */
  detail?: string;
}

/**
 * Whose account the application belongs to.
 *
 * <p>The first question anyone asks, and the one the original panel never answered. It is not
 * obvious: the application is registered ONCE for the whole deployment, by an admin, and every
 * operator then signs in through it — nobody registers their own. It is also nothing to do with the
 * bot credential in the provider registry.
 *
 * <p><b>One rule decides it: match the application to whoever owns the repositories.</b> An earlier
 * version of this guidance named only the shared account and treated a personal one as a
 * compromise, which is wrong for the case it is most often read in — repositories owned by one
 * person. There a personal application is simply the right answer: the two share a fate anyway, so
 * there is nothing to outlive. The mismatch worth avoiding is the other direction, a personal
 * application in front of an organization’s repositories, which leaves when that person does.
 */
export interface OAuthOwner {
  /** Where it goes when the repositories belong to a shared account, named as the platform does. */
  shared: string;
  /** Where it goes when the repositories belong to one person. */
  personal: string;
  /** The rule that decides between them, and what a mismatch costs. */
  detail: string;
}

export interface OAuthSetupGuide {
  /** Human name of the platform (for the "Set up on …" heading). */
  providerLabel: string;
  /** Whose account registers it — rendered above the steps, because it is asked before them. */
  owner: OAuthOwner;
  /** What to enter for a self-managed install, or null when the platform is hosted-only. */
  selfHosted: { web: string; api: string } | null;
  steps: OAuthSetupStep[];
}

const GUIDES: Record<string, OAuthSetupGuide> = {
  github: {
    providerLabel: 'GitHub',
    owner: {
      shared: 'the organization → Settings → Developer settings → OAuth Apps',
      personal: 'your own account → Settings → Developer settings → OAuth Apps',
      detail:
        'Both work identically. Personal repositories take a personal application — that is the ' +
        'right answer rather than a compromise, since the application and the repositories share a ' +
        'fate either way. Only the mismatch costs anything: a personal application in front of an ' +
        'organization’s repositories leaves when that person does, and every operator’s link goes ' +
        'with it.',
    },
    // GitHub is the one platform whose sign-in host and API host genuinely differ, which is why
    // the form has two base fields at all. On Enterprise Server neither is derivable from the other.
    selfHosted: {
      web: 'https://your-github-host',
      api: 'https://your-github-host/api/v3',
    },
    steps: [
      {
        title: 'Open that organization → Settings → Developer settings → OAuth Apps → New OAuth App',
        detail: 'Not the repository’s settings — this lives on the account, not on a repo.',
      },
      {
        title: 'Name it, and set Homepage URL to this dashboard’s address',
        detail: 'The name is what your team will see on the consent screen.',
      },
      {
        title: 'Paste the redirect address above into Authorization callback URL',
        detail: 'It must match exactly — scheme, host, port and path.',
      },
      {
        title: 'Register application, then Generate a new client secret',
        detail: 'GitHub shows the secret once. Copy it before leaving the page.',
      },
      {
        title: 'Paste the Client ID and the secret into the fields above, then Save',
        detail: 'Nothing to configure for permissions: the profile-only scope is requested per sign-in.',
      },
    ],
  },
  gitlab: {
    providerLabel: 'GitLab',
    owner: {
      shared: 'the top-level group → Settings → Applications',
      personal: 'your own account → Edit profile → Applications',
      detail:
        'Both work identically. Personal projects take a personal application — that is the right ' +
        'answer rather than a compromise, since the two share a fate either way. A self-managed ' +
        'instance can also hold one instance-wide under the Admin area, which is the better choice ' +
        'when several groups are reviewed.',
    },
    // One host serves both, so only the sign-in base is asked for; the API base is derived by
    // adding /api/v4. Filling it in by hand is allowed but never necessary.
    selfHosted: { web: 'https://your-gitlab-host', api: 'leave empty' },
    steps: [
      {
        title: 'Open that group → Settings → Applications → Add new application',
        detail: 'Not the project’s settings — this lives on the group, not on a project.',
      },
      { title: 'Paste the redirect address above into Redirect URI' },
      {
        title: 'Tick Confidential',
        detail: 'Required. A non-confidential application has no client secret to give you.',
      },
      { title: 'Under Scopes, tick read_user and nothing else' },
      {
        title: 'Save application, then copy Application ID and Secret into the fields above',
        detail: 'GitLab shows the secret once.',
      },
    ],
  },
  'bitbucket-cloud': {
    providerLabel: 'Bitbucket',
    owner: {
      shared: 'the workspace → Workspace settings → OAuth consumers',
      personal: 'your own workspace → Workspace settings → OAuth consumers',
      detail:
        'The same place either way, and there is no decision to make: Bitbucket gives every account ' +
        'a workspace — a solo one is named after you — and consumers exist only on a workspace.',
    },
    selfHosted: null,
    steps: [
      {
        title: 'Open that workspace → Workspace settings → OAuth consumers → Add consumer',
        detail: 'Not the repository’s settings — this lives on the workspace, not on a repo.',
      },
      {
        title: 'Name it and paste the redirect address above into Callback URL',
        detail: 'Bitbucket calls it the callback URL; it is the same value.',
      },
      {
        title: 'Tick “This is a private consumer”',
        detail: 'Required. A public consumer has no client secret, and the sign-in cannot complete without one.',
      },
      { title: 'Under Permissions → Account, tick Read' },
      {
        title: 'Save, then expand the consumer and copy Key and Secret into the fields above',
        detail: 'Bitbucket’s Key is the client id.',
      },
    ],
  },
};

/** The setup guide for a platform, or null when no portal guide exists for it. */
export function oauthSetupGuide(providerType: string): OAuthSetupGuide | null {
  return GUIDES[providerType] ?? null;
}

/**
 * What to put in a base URL field, tuned to the platform.
 *
 * <p>Blank means the platform's own hosted service on every platform, so the hint's job is to say
 * what a self-managed install needs instead — and, for the one platform where the two fields differ,
 * that they are not the same value.
 */
export function baseUrlHint(providerType: string, which: 'web' | 'api'): string {
  const guide = oauthSetupGuide(providerType);
  if (!guide) {
    return 'Leave empty for the platform’s hosted service.';
  }
  if (!guide.selfHosted) {
    return `Leave empty — ${guide.providerLabel} is hosted only.`;
  }
  const example = which === 'web' ? guide.selfHosted.web : guide.selfHosted.api;
  const what = which === 'web' ? 'where operators sign in' : 'where the API answers';
  return `Empty for the hosted service. Self-managed (${what}): ${example}`;
}
