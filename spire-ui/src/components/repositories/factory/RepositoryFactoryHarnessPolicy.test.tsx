import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../../../api';
import * as build from './buildDefaultsApi';
import { buildSetup, repository } from './factoryFixtures';
import { field, openBuildSetup as open, renderFactory, step, stubFactoryApis } from './factoryTestSetup';

// Harness reviews 3b: which harnesses the build setup offers, and what each may be paid with.
beforeEach(stubFactoryApis);

describe('build setup', () => {
  const offeringKeyedHarness = () => vi.mocked(build.buildOptions).mockResolvedValue({ harnesses: ['codex', 'TEST-keyed'],
    buildHarnesses: ['codex', 'TEST-keyed'], subscriptionHarnesses: ['codex'],
    reportedTypes: { codex: ['INPUT', 'OUTPUT'], 'TEST-keyed': ['INPUT', 'OUTPUT'] } });

  it('keeps the history note out of the Pay with select of the build setup', async () => {
    offeringKeyedHarness();
    renderFactory();
    await open();
    await screen.findByRole('option', { name: 'Codex' });
    expect(screen.getByRole('combobox', { name: 'Pay with' }).textContent).not.toContain('NOT ALLOWED');
  });

  it('switching from a subscription to a harness without one saves API key', async () => {
    offeringKeyedHarness();
    renderFactory();
    await open();
    fireEvent.change(await screen.findByLabelText('Base branch', field), { target: { value: 'main' } });
    fireEvent.change(await screen.findByLabelText('Harness', field), { target: { value: 'codex' } });
    fireEvent.change(await screen.findByLabelText('Pay with', field), { target: { value: 'SUBSCRIPTION' } });
    fireEvent.change(screen.getByLabelText('Harness', field), { target: { value: 'TEST-keyed' } });
    fireEvent.change(await screen.findByLabelText('Model', field), { target: { value: 'TEST-model' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save build setup' }));
    await waitFor(() => expect(build.saveBuildDefaults).toHaveBeenCalledWith(repository.id,
      expect.objectContaining({ harness: 'TEST-keyed', payWith: 'API_KEY' })));
  });

  it('a stored subscription on a harness without one saves API key', async () => {
    offeringKeyedHarness();
    vi.mocked(build.buildDefaults).mockResolvedValue(buildSetup({ revision: 2, baseBranch: 'main', harness: 'TEST-keyed', model: 'TEST-model', payWith: 'SUBSCRIPTION' }));
    renderFactory();
    fireEvent.click(within(await step(5)).getByRole('button', { name: 'Change' }));
    await screen.findByRole('option', { name: 'TEST-keyed' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save build setup' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Save build setup' }));
    await waitFor(() => expect(build.saveBuildDefaults).toHaveBeenCalledWith(repository.id,
      expect.objectContaining({ harness: 'TEST-keyed', payWith: 'API_KEY' })));
  });

  // The options can arrive before the model catalogue; until both are in, the harness lists are not known to be empty.
  it('keeps a stored subscription while the model catalogue is still loading', async () => {
    let resolveModels!: (models: Awaited<ReturnType<typeof api.fetchLlmModels>>) => void;
    const catalogue = await vi.mocked(api.fetchLlmModels)();
    vi.mocked(api.fetchLlmModels).mockReturnValue(new Promise(resolve => { resolveModels = resolve; }));
    vi.mocked(build.buildDefaults).mockResolvedValue(buildSetup({ revision: 2, baseBranch: 'main', harness: 'codex', model: 'TEST-model', payWith: 'SUBSCRIPTION' }));
    renderFactory();
    fireEvent.click(within(await step(5)).getByRole('button', { name: 'Change' }));
    await waitFor(() => expect(build.buildOptions).toHaveBeenCalled());
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    await act(async () => { resolveModels(catalogue); });
    await screen.findByRole('option', { name: 'TEST model (TEST-model)' });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save build setup' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Save build setup' }));
    await waitFor(() => expect(build.saveBuildDefaults).toHaveBeenCalledWith(repository.id,
      expect.objectContaining({ harness: 'codex', payWith: 'SUBSCRIPTION' })));
  });

  // An older server sends no subscription list: that is not a refusal, so the stored seat is saved back unchanged.
  it('keeps a stored subscription when the server sends no subscription list', async () => {
    vi.mocked(build.buildOptions).mockResolvedValue({ harnesses: ['codex'], buildHarnesses: ['codex'],
      reportedTypes: { codex: ['INPUT', 'CACHED_INPUT', 'CACHE_WRITE', 'OUTPUT', 'REASONING'] } } as unknown as build.BuildOptions);
    vi.mocked(build.buildDefaults).mockResolvedValue(buildSetup({ revision: 2, baseBranch: 'main', harness: 'codex', model: 'TEST-model', payWith: 'SUBSCRIPTION' }));
    renderFactory();
    fireEvent.click(within(await step(5)).getByRole('button', { name: 'Change' }));
    await screen.findByRole('option', { name: 'TEST model (TEST-model)' });
    // What is saved is what the select shows: the stored seat, named as one this server does not offer.
    expect(screen.getByRole('combobox', { name: 'Pay with' })).toHaveValue('SUBSCRIPTION');
    expect(screen.getByRole('option', { name: 'a subscription seat (not offered here)' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save build setup' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Save build setup' }));
    await waitFor(() => expect(build.saveBuildDefaults).toHaveBeenCalledWith(repository.id,
      expect.objectContaining({ harness: 'codex', payWith: 'SUBSCRIPTION' })));
  });

  // A model belongs to the harness that offers it: the old slug must not ride along to a harness that never listed it.
  it('drops a model the new harness does not offer', async () => {
    const types = ['INPUT', 'OUTPUT'];
    const offering = (slug: string) => ({ status: 'OK' as const,
      offered: [{ slug, displayName: slug, defaultEffort: '', efforts: [], visible: true, priority: 1 }] });
    vi.mocked(build.buildOptions).mockResolvedValue({ harnesses: ['codex', 'TEST-other'], buildHarnesses: ['codex', 'TEST-other'],
      subscriptionHarnesses: ['codex'], reportedTypes: { codex: types, 'TEST-other': types },
      models: { codex: offering('TEST-model'), 'TEST-other': offering('TEST-other-model') } });
    vi.mocked(build.buildDefaults).mockResolvedValue(buildSetup({ revision: 2, baseBranch: 'main', harness: 'codex', model: 'TEST-model' }));
    renderFactory();
    fireEvent.click(within(await step(5)).getByRole('button', { name: 'Change' }));
    await screen.findByRole('option', { name: 'TEST-other' });
    fireEvent.change(screen.getByRole('combobox', { name: 'Harness' }), { target: { value: 'TEST-other' } });
    expect(screen.getByRole('combobox', { name: 'Model' })).toHaveValue('');
    expect(screen.queryByRole('option', { name: /TEST-model/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Save build setup' })).toBeDisabled();
    expect(build.saveBuildDefaults).not.toHaveBeenCalled();
  });

  // Harness reviews 3b: a review-only harness is configured but may not build.
  it('lists only build harnesses in the build setup', async () => {
    vi.mocked(build.buildOptions).mockResolvedValue({ harnesses: ['codex', 'claude_code'], buildHarnesses: ['codex'],
      subscriptionHarnesses: ['codex'], reportedTypes: { codex: ['INPUT', 'OUTPUT'] } });
    renderFactory();
    await open();
    await screen.findByRole('option', { name: 'Codex' });
    expect(screen.queryByRole('option', { name: 'Claude Code' })).toBeNull();
  });

  it('offers a subscription only to a harness that may use one', async () => {
    vi.mocked(build.buildOptions).mockResolvedValue({ harnesses: ['codex', 'TEST-keyed'], buildHarnesses: ['codex', 'TEST-keyed'],
      subscriptionHarnesses: ['codex'], reportedTypes: { codex: ['INPUT', 'OUTPUT'] } });
    renderFactory();
    await open();
    fireEvent.change(await screen.findByLabelText('Harness', field), { target: { value: 'TEST-keyed' } });
    const pay = screen.getByRole('combobox', { name: 'Pay with' });
    expect(within(pay).queryByRole('option', { name: /subscription/ })).toBeNull();
  });
});
