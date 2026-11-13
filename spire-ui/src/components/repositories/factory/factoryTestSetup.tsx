import { fireEvent, render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import * as sources from '../../work-items/workSourcesApi';
import * as policies from '../../work-items/workPolicyApi';
import * as api from '../../../api';
import * as build from './buildDefaultsApi';
import * as land from './landSettingsApi';
import RepositoryFactory from './RepositoryFactory';
import { account, buildSetup, landSetup, policy, profile, repository, source } from './factoryFixtures';
import { queryWrapper } from '../../../test/queryWrapper';

/** Test setup only: the Factory tab rendered over stubbed APIs, shared by the RepositoryFactory test files. */
export const field = { selector: 'input,select,textarea' };
export const renderFactory = (repo = repository) => render(<RepositoryFactory repository={repo} accounts={[account()]}
  webhooks={{ hooks: [], unavailable: false, changed: vi.fn() }} />, { wrapper: queryWrapper() });
export const step = (number: number) => screen.findByRole('listitem', { name: (accessible) => accessible.startsWith(`Step ${number}:`) });
export const openBuildSetup = async () => { fireEvent.click(await screen.findByRole('button', { name: 'Set up the build' })); };
export function stubFactoryApis() {
  vi.spyOn(sources, 'fetchWorkSources').mockResolvedValue([source()]);
  vi.spyOn(sources, 'saveWorkActor').mockResolvedValue(source());
  vi.spyOn(sources, 'removeWorkActor').mockResolvedValue(source());
  vi.spyOn(policies, 'profiles').mockResolvedValue([profile]);
  vi.spyOn(policies, 'policy').mockResolvedValue(policy());
  vi.spyOn(policies, 'savePolicy').mockResolvedValue(policy({ revision: 8 }));
  vi.spyOn(policies, 'saveProfile').mockImplementation(async value => value);
  vi.spyOn(build, 'buildDefaults').mockResolvedValue(buildSetup());
  vi.spyOn(build, 'buildOptions').mockResolvedValue({ harnesses: ['codex'], buildHarnesses: ['codex'], subscriptionHarnesses: ['codex'], reportedTypes: { codex: ['INPUT','CACHED_INPUT','CACHE_WRITE','OUTPUT','REASONING'] } });
  vi.spyOn(build, 'saveBuildDefaults').mockResolvedValue(buildSetup({ revision: 1, baseBranch: 'main', harness: 'codex', model: 'TEST-model' }));
  vi.spyOn(land, 'landSettings').mockResolvedValue(landSetup());
  vi.spyOn(land, 'landOptions').mockResolvedValue({ checked: true, allowed: ['merge', 'squash', 'rebase'] });
  vi.spyOn(build, 'repositoryBranchHead').mockResolvedValue({ branch: 'main', commit: 'a'.repeat(40), account: 'FACTORY' });
  vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([
    // Complete for codex: priced where this fake vendor bills, asserted where it does not.
    { id: 'TEST-model-id', type: 'openai', name: 'TEST-model', label: 'TEST model', pricingMode: 'METERED', rates: { INPUT: 100, OUTPUT: 200 },
      outputTokenParam: 'MAX_TOKENS', supportsTemperature: true, reasoningEffort: null, extraParams: {}, enabled: true, createdAt: '2026-09-16T00:00:00Z', notBilled: ['CACHED_INPUT', 'CACHE_WRITE', 'REASONING'] },
    { id: 'TEST-unpriced-id', type: 'openai', name: 'TEST-unpriced', label: 'TEST unpriced', pricingMode: 'METERED', rates: { INPUT: 100 },
      outputTokenParam: 'MAX_TOKENS', supportsTemperature: true, reasoningEffort: null, extraParams: {}, enabled: true, createdAt: '2026-09-16T00:00:00Z', notBilled: [] },
  ]);
}
