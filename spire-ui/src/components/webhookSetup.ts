// Per-provider "what to do on the portal" steps, shown in the one-time reveal after a webhook is
// created (Settings → Webhooks, and the repository screens). The reveal already shows the Payload
// URL + Secret to copy, so these steps reference "above" for those two values and cover the rest:
// where to go, and which events to enable. The events depend on the webhook kind: the REVIEWER
// hook feeds the reviewer, the FACTORY hook watches the factory's own pull requests and branches,
// and the ISSUE hook feeds work-item intake. Kept framework-free so it unit-tests without rendering.

import type { WebhookEventKind } from '../api';

export interface WebhookSetupStep {
  /** Short imperative — the action for this step. */
  title: string;
  /** Optional secondary line (a field name, a note about signing, etc.). */
  detail?: string;
  /** Optional list of events/triggers to enable, rendered as chips. */
  events?: string[];
}

export interface WebhookSetupGuide {
  /** Human name of the provider (for the "Set up on …" heading). */
  providerLabel: string;
  steps: WebhookSetupStep[];
}

/** The event steps of one kind: one per portal section, with the forge's own labels. */
type EventSteps = Partial<Record<WebhookEventKind, WebhookSetupStep[]>>;

interface ProviderGuide {
  providerLabel: string;
  /** What each kind of hook must subscribe to on this forge; a kind the forge cannot feed is absent. */
  events: EventSteps;
  /** The portal steps around the chosen kind's event steps, ending with a check that fits the kind. */
  steps: (eventSteps: WebhookSetupStep[], kind: WebhookEventKind) => WebhookSetupStep[];
}

// Each list mirrors what the forge adapter accepts for that kind: GitHubIngress.translate (REVIEWER),
// GitHubIngress.activity (FACTORY) and GitHubWorkIngress (ISSUE), and their GitLab and Bitbucket twins.
const GITHUB_EVENTS = 'Choose “Let me select individual events” and enable:';
const GITLAB_EVENTS = 'Under Trigger, enable:';
// GitLab renamed the trigger; issues still arrive as an Issue Hook, so the guide names both labels.
const GITLAB_ISSUES = 'Work item events (Issues events on older GitLab)';
const BITBUCKET_PULL_REQUEST = 'Under Triggers → Pull request, enable:';

const GUIDES: Record<string, ProviderGuide> = {
  github: {
    providerLabel: 'GitHub',
    events: {
      REVIEWER: [{ title: GITHUB_EVENTS, events: ['Pull requests', 'Issue comments', 'Pull request review comments'] }],
      FACTORY: [{ title: GITHUB_EVENTS, events: ['Pushes', 'Pull requests', 'Pull request reviews', 'Pull request review comments', 'Issue comments'] }],
      ISSUE: [{ title: GITHUB_EVENTS, events: ['Issues', 'Issue comments'] }],
    },
    steps: (eventSteps) => [
      { title: 'Open your repo → Settings → Webhooks → Add webhook' },
      { title: 'Paste the Payload URL above', detail: 'Content type: application/json. Keep SSL verification on.' },
      { title: 'Paste the secret above into Secret', detail: 'GitHub signs each delivery (X-Hub-Signature-256).' },
      ...eventSteps,
      { title: 'Add webhook', detail: 'GitHub sends a test ping — expect a 204.' },
    ],
  },
  gitlab: {
    providerLabel: 'GitLab',
    events: {
      REVIEWER: [{ title: GITLAB_EVENTS, events: ['Merge request events', 'Comments'] }],
      FACTORY: [{ title: GITLAB_EVENTS, events: ['Push events', 'Merge request events', 'Comments'] }],
      ISSUE: [{ title: GITLAB_EVENTS, events: [GITLAB_ISSUES, 'Comments'] }],
    },
    steps: (eventSteps, kind) => [
      { title: 'Open your project → Settings → Webhooks → Add new webhook' },
      { title: 'Paste the Payload URL above into URL' },
      {
        title: 'Paste the secret above into Secret token',
        detail: 'GitLab returns it in X-Gitlab-Token — it does not sign the body.',
      },
      ...eventSteps,
      {
        title: 'Add webhook',
        detail: `Then use Test → ${kind === 'ISSUE' ? GITLAB_ISSUES : 'Merge request events'} to confirm a 2xx.`,
      },
    ],
  },
  'bitbucket-cloud': {
    providerLabel: 'Bitbucket',
    events: {
      REVIEWER: [{ title: BITBUCKET_PULL_REQUEST, events: ['Created', 'Updated', 'Comment created', 'Merged', 'Declined'] }],
      FACTORY: [
        { title: 'Under Triggers → Repository, enable:', events: ['Push'] },
        { title: BITBUCKET_PULL_REQUEST, events: ['Updated', 'Merged', 'Declined', 'Comment created', 'Approved', 'Approval removed'] },
      ],
    },
    steps: (eventSteps, kind) => [
      { title: 'Open your repo → Repository settings → Webhooks → Add webhook' },
      { title: 'Paste the Payload URL above into URL', detail: 'Set Status to Active.' },
      { title: 'Paste the secret above into Secret', detail: 'Bitbucket signs each delivery (X-Hub-Signature).' },
      ...eventSteps,
      {
        title: 'Save',
        detail: kind === 'FACTORY'
          ? 'A push to an item branch, or a change on an item’s pull request, now reaches the factory.'
          : 'Open a non-draft PR by an allowed author to fire the first review.',
      },
    ],
  },
};

/**
 * The setup guide for a provider type and webhook kind, or null when no portal guide exists for the
 * provider or the provider cannot feed that kind. The kind defaults to REVIEWER, the hook every
 * registration starts with.
 */
export function webhookSetupGuide(providerType: string, eventKind: WebhookEventKind = 'REVIEWER'): WebhookSetupGuide | null {
  const guide = GUIDES[providerType];
  const eventSteps = guide?.events[eventKind];
  if (!guide || !eventSteps) return null;
  return { providerLabel: guide.providerLabel, steps: guide.steps(eventSteps, eventKind) };
}

// What to type in the target field differs per provider — GitHub owner/repo, a Bitbucket
// workspace/slug, a GitLab group/project (with a real constraint: nested GitLab groups can't
// use repo scope, because the gateway matches an exact one-slash owner/repo, so they must
// register the top-level group under org scope).

export interface WebhookTargetHelp {
  /** Example value for the field's placeholder. */
  placeholder: string;
  /** One line explaining what to enter for this provider + scope. */
  hint: string;
}

const TARGET_HELP: Record<string, { repo: WebhookTargetHelp; org: WebhookTargetHelp }> = {
  github: {
    repo: { placeholder: 'octocat/hello-world', hint: 'GitHub owner and repo name — the two parts of the repo URL.' },
    org: { placeholder: 'octocat', hint: 'GitHub organization (or user) login. Every repo under it is covered.' },
  },
  gitlab: {
    repo: {
      placeholder: 'my-team/api',
      hint: 'GitLab group and project. A nested group (group/subgroup/project) does not fit here — use Organization scope with the top-level group instead.',
    },
    org: { placeholder: 'my-team', hint: 'GitLab top-level group. Every project under it, including nested subgroups, is covered.' },
  },
  'bitbucket-cloud': {
    repo: { placeholder: 'my-workspace/api', hint: 'Bitbucket workspace ID and repository slug — the two parts of the repo URL.' },
    org: { placeholder: 'my-workspace', hint: 'Bitbucket workspace ID. Every repo in the workspace is covered.' },
  },
};

const GENERIC_TARGET_HELP: { repo: WebhookTargetHelp; org: WebhookTargetHelp } = {
  repo: { placeholder: 'owner/repo', hint: 'One repository. Paste this webhook into that repo’s settings.' },
  org: { placeholder: 'owner', hint: 'Every repository under this owner. Paste this webhook into the owner’s settings.' },
};

/** Placeholder + hint for the target field, tuned to the provider and scope. */
export function webhookTargetHelp(providerType: string, scope: 'repo' | 'org'): WebhookTargetHelp {
  return (TARGET_HELP[providerType] ?? GENERIC_TARGET_HELP)[scope];
}
