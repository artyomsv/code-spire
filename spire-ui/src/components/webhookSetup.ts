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

type EventsByKind = Partial<Record<WebhookEventKind, string[]>>;

interface ProviderGuide {
  providerLabel: string;
  /** What each kind of hook must subscribe to on this forge; a kind the forge cannot feed is absent. */
  events: EventsByKind;
  /** The portal steps, given the chosen kind's events. */
  steps: (events: string[]) => WebhookSetupStep[];
}

// Each list mirrors what the forge adapter accepts for that kind: GitHubIngress.translate (REVIEWER),
// GitHubIngress.activity (FACTORY) and GitHubWorkIngress (ISSUE), and their GitLab and Bitbucket twins.
const GUIDES: Record<string, ProviderGuide> = {
  github: {
    providerLabel: 'GitHub',
    events: {
      REVIEWER: ['Pull requests', 'Issue comments', 'Pull request review comments'],
      FACTORY: ['Pushes', 'Pull requests', 'Pull request reviews', 'Pull request review comments', 'Issue comments'],
      ISSUE: ['Issues', 'Issue comments'],
    },
    steps: (events) => [
      { title: 'Open your repo → Settings → Webhooks → Add webhook' },
      { title: 'Paste the Payload URL above', detail: 'Content type: application/json. Keep SSL verification on.' },
      { title: 'Paste the secret above into Secret', detail: 'GitHub signs each delivery (X-Hub-Signature-256).' },
      { title: 'Choose “Let me select individual events” and enable:', events },
      { title: 'Add webhook', detail: 'GitHub sends a test ping — expect a 204.' },
    ],
  },
  gitlab: {
    providerLabel: 'GitLab',
    events: {
      REVIEWER: ['Merge request events', 'Comments'],
      FACTORY: ['Push events', 'Merge request events', 'Comments'],
      ISSUE: ['Issues events', 'Comments'],
    },
    steps: (events) => [
      { title: 'Open your project → Settings → Webhooks → Add new webhook' },
      { title: 'Paste the Payload URL above into URL' },
      {
        title: 'Paste the secret above into Secret token',
        detail: 'GitLab returns it in X-Gitlab-Token — it does not sign the body.',
      },
      { title: 'Under Trigger, enable:', events },
      { title: 'Add webhook', detail: 'Then use Test → Merge request events to confirm a 2xx.' },
    ],
  },
  'bitbucket-cloud': {
    providerLabel: 'Bitbucket',
    events: {
      REVIEWER: ['Created', 'Updated', 'Comment created', 'Merged', 'Declined'],
      FACTORY: ['Push', 'Updated', 'Merged', 'Declined', 'Comment created', 'Approved', 'Unapproved'],
    },
    steps: (events) => [
      { title: 'Open your repo → Repository settings → Webhooks → Add webhook' },
      { title: 'Paste the Payload URL above into URL', detail: 'Set Status to Active.' },
      { title: 'Paste the secret above into Secret', detail: 'Bitbucket signs each delivery (X-Hub-Signature).' },
      {
        title: events.includes('Push') ? 'Under Triggers, enable Repository → Push and Pull request →' : 'Under Triggers → Pull request, enable:',
        events,
      },
      { title: 'Save', detail: 'Open a non-draft PR by an allowed author to fire the first review.' },
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
  const events = guide?.events[eventKind];
  if (!guide || !events) return null;
  return { providerLabel: guide.providerLabel, steps: guide.steps(events) };
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
