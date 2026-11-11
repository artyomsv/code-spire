import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
import * as api from '../../api';
import { queryWrapper } from '../../test/queryWrapper';
import * as build from './factory/buildDefaultsApi';
import ReviewHarnessFields from './ReviewHarnessFields';
import type { ReviewHarness } from './repositoriesApi';

afterEach(cleanup);

const OPTIONS: build.BuildOptions = {
  harnesses: ['codex', 'claude_code'], subscriptionHarnesses: ['codex'], buildHarnesses: ['codex'],
  reportedTypes: { codex: ['INPUT', 'OUTPUT'], claude_code: ['INPUT', 'OUTPUT'] },
};

let latest: ReviewHarness;
function Host({ initial }: { initial: ReviewHarness }) {
  const [value, setValue] = useState(initial);
  latest = value;
  return <ReviewHarnessFields repositoryId="TEST-repo" value={value} onChange={update => setValue(update)} />;
}

async function show(initial: ReviewHarness) {
  vi.spyOn(build, 'buildOptions').mockResolvedValue(OPTIONS);
  vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([]);
  render(<Host initial={initial} />, { wrapper: queryWrapper() });
  await screen.findByRole('option', { name: 'Claude Code' });
}

const payValues = () => within(screen.getByRole('combobox', { name: 'Pay with' })).getAllByRole('option')
  .map(option => (option as HTMLOptionElement).value);

it('offers only API key for Claude Code', async () => {
  await show({ harness: 'claude_code', model: '', effort: null, payWith: 'API_KEY' });
  expect(payValues()).toEqual(['API_KEY']);
  expect(screen.queryByRole('option', { name: /subscription/i })).toBeNull();
});

it('offers both payments for Codex', async () => {
  await show({ harness: 'codex', model: '', effort: null, payWith: 'API_KEY' });
  expect(payValues()).toEqual(['API_KEY', 'SUBSCRIPTION']);
});

it('switching to Claude Code resets a subscription to API key', async () => {
  await show({ harness: 'codex', model: '', effort: null, payWith: 'SUBSCRIPTION' });
  fireEvent.change(screen.getByRole('combobox', { name: 'Harness' }), { target: { value: 'claude_code' } });
  await waitFor(() => expect(latest.payWith).toBe('API_KEY'));
  expect(latest.harness).toBe('claude_code');
});

it('names harnesses by label', async () => {
  await show({ harness: '', model: '', effort: null, payWith: 'API_KEY' });
  const option = screen.getByRole('option', { name: 'Claude Code' }) as HTMLOptionElement;
  expect(option.value).toBe('claude_code');
  expect(screen.getByRole('option', { name: 'Codex' })).toBeInTheDocument();
});

// A JSX child that starts with // is text, not a comment: the history note must not reach the page.
it('keeps the history note out of the Pay with select', async () => {
  await show({ harness: 'codex', model: '', effort: null, payWith: 'API_KEY' });
  expect(screen.getByRole('combobox', { name: 'Pay with' }).textContent).not.toContain('NOT ALLOWED');
});

it('normalises a stored subscription on a harness that does not allow one to API key', async () => {
  await show({ harness: 'claude_code', model: '', effort: null, payWith: 'SUBSCRIPTION' });
  await waitFor(() => expect(latest.payWith).toBe('API_KEY'));
  expect(latest.harness).toBe('claude_code');
});

it('keeps a stored subscription on a harness that allows one', async () => {
  await show({ harness: 'codex', model: '', effort: null, payWith: 'SUBSCRIPTION' });
  expect(latest.payWith).toBe('SUBSCRIPTION');
  expect(screen.getByRole('combobox', { name: 'Pay with' })).toHaveValue('SUBSCRIPTION');
});
