import { describe, expect, it } from 'vitest';
import { webhookSetupGuide, webhookTargetHelp } from './webhookSetup';

describe('webhookSetupGuide', () => {
  it('returns GitHub steps including its three event types', () => {
    const guide = webhookSetupGuide('github');
    expect(guide?.providerLabel).toBe('GitHub');
    const events = (guide?.steps ?? []).flatMap((s) => s.events ?? []);
    expect(events).toEqual(
      expect.arrayContaining(['Pull requests', 'Issue comments', 'Pull request review comments']),
    );
  });

  it('returns GitLab merge-request + comments triggers', () => {
    const events = (webhookSetupGuide('gitlab')?.steps ?? []).flatMap((s) => s.events ?? []);
    expect(events).toEqual(expect.arrayContaining(['Merge request events', 'Comments']));
  });

  it('returns Bitbucket pull-request triggers', () => {
    const events = (webhookSetupGuide('bitbucket-cloud')?.steps ?? []).flatMap((s) => s.events ?? []);
    expect(events).toEqual(
      expect.arrayContaining(['Created', 'Updated', 'Comment created', 'Merged', 'Declined']),
    );
  });

  it('gives every step a non-empty title', () => {
    for (const providerType of ['github', 'gitlab', 'bitbucket-cloud']) {
      for (const step of webhookSetupGuide(providerType)!.steps) {
        expect(step.title.length).toBeGreaterThan(0);
      }
    }
  });

  it('returns null for a provider without a guide', () => {
    expect(webhookSetupGuide('bitbucket-dc')).toBeNull();
    expect(webhookSetupGuide('')).toBeNull();
  });
});

describe('webhookTargetHelp', () => {
  it('gives provider-specific examples for repo scope', () => {
    expect(webhookTargetHelp('github', 'repo').placeholder).toBe('octocat/hello-world');
    expect(webhookTargetHelp('gitlab', 'repo').placeholder).toBe('my-team/api');
    expect(webhookTargetHelp('bitbucket-cloud', 'repo').placeholder).toBe('my-workspace/api');
  });

  it('warns that nested GitLab groups need org scope', () => {
    expect(webhookTargetHelp('gitlab', 'repo').hint).toMatch(/nested|subgroup|top-level group/i);
  });

  it('names the right container for org scope', () => {
    expect(webhookTargetHelp('github', 'org').hint).toMatch(/organization|user/i);
    expect(webhookTargetHelp('gitlab', 'org').hint).toMatch(/group/i);
    expect(webhookTargetHelp('bitbucket-cloud', 'org').hint).toMatch(/workspace/i);
  });

  it('falls back to generic help for an unknown provider', () => {
    expect(webhookTargetHelp('bitbucket-dc', 'repo').placeholder).toBe('owner/repo');
    expect(webhookTargetHelp('', 'org').placeholder).toBe('owner');
  });
});

describe('webhookSetupGuide per webhook kind', () => {
  const events = (providerType: string, kind: 'REVIEWER' | 'FACTORY' | 'ISSUE') =>
    (webhookSetupGuide(providerType, kind)?.steps ?? []).flatMap((s) => s.events ?? []);

  it('defaults to the reviewer events', () => {
    expect(webhookSetupGuide('github')).toEqual(webhookSetupGuide('github', 'REVIEWER'));
  });

  it('lists what the GitHub factory hook must send: pushes, pull requests, reviews and comments', () => {
    expect(events('github', 'FACTORY')).toEqual(
      ['Pushes', 'Pull requests', 'Pull request reviews', 'Pull request review comments', 'Issue comments'],
    );
  });

  it('lists only issues and issue comments for the GitHub issue hook', () => {
    expect(events('github', 'ISSUE')).toEqual(['Issues', 'Issue comments']);
  });

  it('lists the GitLab triggers per kind', () => {
    expect(events('gitlab', 'FACTORY')).toEqual(['Push events', 'Merge request events', 'Comments']);
    expect(events('gitlab', 'ISSUE')).toEqual(['Issues events', 'Comments']);
  });

  it('lists the Bitbucket factory triggers per section and has no issue hook', () => {
    const steps = webhookSetupGuide('bitbucket-cloud', 'FACTORY')!.steps.filter((s) => s.events);
    expect(steps.map((s) => s.title)).toEqual(['Under Triggers → Repository, enable:', 'Under Triggers → Pull request, enable:']);
    expect(steps[0].events).toEqual(['Push']);
    expect(steps[1].events).toEqual(['Updated', 'Merged', 'Declined', 'Comment created', 'Approved', 'Approval removed']);
    expect(webhookSetupGuide('bitbucket-cloud', 'ISSUE')).toBeNull();
  });

  it('ends with a check that fits the kind, not the reviewer', () => {
    const last = (providerType: string, kind: 'REVIEWER' | 'FACTORY' | 'ISSUE') => {
      const steps = webhookSetupGuide(providerType, kind)!.steps;
      return steps[steps.length - 1].detail ?? '';
    };
    expect(last('gitlab', 'ISSUE')).toMatch(/Test → Issues events/);
    expect(last('gitlab', 'REVIEWER')).toMatch(/Test → Merge request events/);
    expect(last('bitbucket-cloud', 'FACTORY')).not.toMatch(/review/i);
    expect(last('bitbucket-cloud', 'REVIEWER')).toMatch(/first review/);
  });

  it('keeps the portal steps and the ping check for every kind', () => {
    for (const kind of ['REVIEWER', 'FACTORY', 'ISSUE'] as const) {
      const titles = webhookSetupGuide('github', kind)!.steps.map((s) => s.title);
      expect(titles[0]).toMatch(/Settings → Webhooks → Add webhook/);
      expect(titles[titles.length - 1]).toBe('Add webhook');
    }
  });
});
