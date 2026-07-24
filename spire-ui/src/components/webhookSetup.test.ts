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
