import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import * as api from '../api';
import type { LlmModelView } from '../api';
import SettingsLlmModelForm from './SettingsLlmModelForm';

/**
 * Ticking "the vendor does not bill this" on a model that already has a rate (M3.5 part D).
 *
 * <p>The input goes blank and disabled the moment the box is ticked, so the rate has to leave the
 * form's state with it. The first version left the number behind: the save was refused for carrying
 * both, about a value nobody could see or edit, and the operator had to untick it, clear the field by
 * hand and tick it again.
 */
const saved: LlmModelView = {
  id: 'TEST-model-id', type: 'openai', name: 'TEST-model', label: 'TEST model', pricingMode: 'METERED',
  rates: { INPUT: 250_000, OUTPUT: 1_000_000, CACHED_INPUT: 30_000 }, notBilled: [],
  outputTokenParam: 'MAX_TOKENS', supportsTemperature: true, reasoningEffort: null, extraParams: {},
  enabled: true, createdAt: '2026-09-16T00:00:00Z',
};

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(api, 'updateLlmModel').mockResolvedValue(saved);
});

it('replaces an existing rate with the assertion, in one tick', async () => {
  render(<SettingsLlmModelForm initial={saved} onClose={vi.fn()} onSaved={async () => {}} />);
  const box = screen.getByLabelText('Cached input: the vendor does not bill this');

  fireEvent.click(box);
  expect(screen.getByRole('button', { name: /Save/ })).toBeEnabled();

  fireEvent.click(screen.getByRole('button', { name: /Save/ }));
  await waitFor(() => expect(api.updateLlmModel).toHaveBeenCalled());
  const [, input] = vi.mocked(api.updateLlmModel).mock.calls[0];
  expect(input.notBilled).toEqual(['CACHED_INPUT']);
  expect(input.rates.CACHED_INPUT).toBeUndefined();
  // The two priced dimensions are untouched: only the ticked one changed.
  expect(input.rates.INPUT).toBe(250_000);
  expect(input.rates.OUTPUT).toBe(1_000_000);
});

it('shows no error, because the form never holds a rate and an assertion at once', async () => {
  render(<SettingsLlmModelForm initial={saved} onClose={vi.fn()} onSaved={async () => {}} />);
  fireEvent.click(screen.getByLabelText('Cached input: the vendor does not bill this'));
  fireEvent.click(screen.getByRole('button', { name: /Save/ }));
  await waitFor(() => expect(api.updateLlmModel).toHaveBeenCalled());
  expect(screen.queryByText(/both a rate and/)).toBeNull();
});
