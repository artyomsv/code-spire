import type { ProviderView, WebhookRepoView } from '../../../api';
import type { Repository } from '../repositoriesApi';
import type { Policy, Profile, Phase } from '../../work-items/workPolicyApi';
import type { WorkSource } from '../../work-items/workSourcesApi';

/** Test fixtures only. Every identifier is TEST-prefixed and every host is under example.test. */
export function account(type = 'github', overrides: Partial<ProviderView> = {}): ProviderView {
  return { id: `TEST-${type}`, name: `TEST-${type} account`, type, baseUrl: `https://${type}.example.test`, authKind: type === 'atlassian' ? 'basic' : 'bearer',
    authUsername: null, hasSecret: true, botAccountId: '900001', enabled: true, authors: [], conversationLevel: null,
    role: type === 'atlassian' ? 'CONTEXT' : 'FACTORY', botUsername: 'TEST-bot', createdAt: '2026-09-13T12:00:00Z', lastCheckAt: null, lastCheckOk: null, lastCheckError: null, ...overrides };
}
export const repository: Repository = { id: 'TEST-repository', scmType: 'github', forgeOrigin: 'https://github.example.test', workspace: 'TEST-owner', slug: 'TEST-repo',
  enabled: true, revision: 1, reviewer: null, factory: null };
export function source(overrides: Partial<WorkSource> = {}): WorkSource {
  return { id: 'TEST-source', name: 'TEST-source name', type: 'GITHUB', origin: 'https://github.example.test', projectId: '10001', scope: 'TEST-owner/TEST-repo',
    repositoryId: repository.id, accountId: 'TEST-github', enabled: true, configuredEnabled: true, version: { source: 4, repository: 1, account: 1 }, cursor: null,
    health: 'healthy', allowedPeople: [{ providerUserId: '900123', handle: 'TEST-person', displayName: 'TEST-person name' }],
    repository: { workspace: 'TEST-owner', slug: 'TEST-repo' }, ...overrides };
}
const off: Record<Phase, string> = { INTAKE: 'off', SPEC: 'off', PLAN: 'off', BUILD: 'off', VERIFY: 'off', DELIVER: 'off', REVIEW: 'off', LAND: 'off' };
export const profile: Profile = { id: 'TEST-profile', name: 'TEST-assisted', version: 3, precedence: 10,
  modes: { ...off, INTAKE: 'auto', SPEC: 'auto', PLAN: 'approve', BUILD: 'auto' },
  limits: { gateTtlSeconds: 3600, maxRunsPerItem: 5, maxStepsPerPlan: 20, maxWallClockSeconds: 7200, maxCostMillicents: 2_000_000, maxCallsPerItem: 40, protectedPaths: [] } };
export const policy = (overrides: Partial<Policy> = {}): Policy => ({ revision: 7, ceiling: profile, mappings: { 'TEST-work': profile }, ...overrides });
export const issueHook: WebhookRepoView = { id: 'TEST-hook', repositoryId: repository.id, eventKind: 'ISSUE', sourceId: 'TEST-source', forgeOrigin: repository.forgeOrigin,
  providerType: 'github', scope: 'repo', target: 'TEST-owner/TEST-repo', webhookKey: 'TEST-key', hasSecret: true, enabled: true, createdAt: '2026-09-15T00:00:00Z' };
