import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import type { LlmModelView } from '../../../api';
import BuildModelFields, { modelChoices } from './BuildModelFields';
import type { HarnessModels } from './buildDefaultsApi';

afterEach(cleanup);

/** A priced catalogue entry. Placeholder names: this is about the rules, not today's vendor list. */
function priced(name: string, notBilled: string[] = []): LlmModelView {
  return {
    id: `TEST-${name}`, type: 'openai', name, label: name, pricingMode: 'METERED',
    rates: { INPUT: 1, OUTPUT: 1 }, outputTokenParam: 'MAX_TOKENS', supportsTemperature: true,
    reasoningEffort: null, extraParams: {}, enabled: true, createdAt: '1970-01-01T00:00:00Z', notBilled,
  } as unknown as LlmModelView;
}

const REPORTED = { codex: ['INPUT', 'OUTPUT'] };

const KNOWN: HarnessModels = {
  status: 'OK',
  offered: [
    { slug: 'TEST-fast', displayName: 'Test Fast', defaultEffort: 'low', efforts: ['low', 'medium'], visible: true, priority: 1 },
    { slug: 'TEST-deep', displayName: 'Test Deep', defaultEffort: 'high', efforts: ['medium', 'high', 'xhigh'], visible: true, priority: 2 },
  ],
};

// The operator's report: codex was offered Claude and Gemini. When the image says what it runs, the
// price list must not add models of its own — it only decides whether each one can be paid for.
it('offers the models the harness runs, not everything on the price list', () => {
  const choices = modelChoices('codex', KNOWN, [priced('TEST-fast'), priced('TEST-claude'), priced('TEST-gemini')], REPORTED);

  expect(choices.map(choice => choice.value)).toEqual(['TEST-fast', 'TEST-deep']);
});

// A model the harness runs but nobody has priced yet is SHOWN — so the operator learns it exists — and
// blocked with the reason, because an API-key run cannot be paid for without a rate.
it('shows a model the harness runs but that has no price, and says what is missing', () => {
  const choices = modelChoices('codex', KNOWN, [priced('TEST-fast')], REPORTED);

  const deep = choices.find(choice => choice.value === 'TEST-deep');
  expect(deep?.blocked).toMatch(/no price yet/);
  expect(choices.find(choice => choice.value === 'TEST-fast')?.blocked).toBeNull();
});

// When the image did not say, the price list is all there is — what this screen offered before — and it
// is not dressed up as the harness's own list.
it('falls back to the price list, and says why, when the harness list is unknown', () => {
  const unknown: HarnessModels = { status: 'NO_CATALOGUE', offered: [] };
  const choices = modelChoices('codex', unknown, [priced('TEST-any')], REPORTED);

  expect(choices.map(choice => choice.value)).toEqual(['TEST-any']);

  render(<BuildModelFields harness="codex" known={unknown} choices={choices} model="" effort=""
    setModel={() => { }} setEffort={() => { }} />);
  expect(screen.getByRole('status')).toHaveTextContent(/built without deploy\/agent\/build-codex\.sh/);
});

// The levels belong to the model: Codex itself offers "Model and Effort" together, and they differ per
// model. Choosing a different model must rebuild the list from THAT model.
it('offers the thinking levels of the chosen model, with its own default named', () => {
  const choices = modelChoices('codex', KNOWN, [priced('TEST-fast'), priced('TEST-deep')], REPORTED);
  render(<BuildModelFields harness="codex" known={KNOWN} choices={choices} model="TEST-deep" effort=""
    setModel={() => { }} setEffort={() => { }} />);

  const levels = Array.from(screen.getByRole('combobox', { name: 'Thinking level' }).querySelectorAll('option')).map(option => option.textContent);
  expect(levels).toEqual(['Model default (high)', 'medium', 'high', 'xhigh']);
});

// Nothing honest to offer when the image did not say which levels exist, and the save refuses a level it
// cannot check — so no level control is shown at all.
it('offers no thinking level when the harness list is unknown', () => {
  const unknown: HarnessModels = { status: 'UNKNOWN', offered: [] };
  render(<BuildModelFields harness="codex" known={unknown} choices={modelChoices('codex', unknown, [priced('TEST-any')], REPORTED)}
    model="TEST-any" effort="" setModel={() => { }} setEffort={() => { }} />);

  expect(screen.queryByRole('combobox', { name: 'Thinking level' })).not.toBeInTheDocument();
});

// A level chosen for one model is not carried to the next: another model may not offer it at all.
it('clears the thinking level when the model changes', () => {
  let cleared = 'not-called';
  const choices = modelChoices('codex', KNOWN, [priced('TEST-fast'), priced('TEST-deep')], REPORTED);
  render(<BuildModelFields harness="codex" known={KNOWN} choices={choices} model="TEST-deep" effort="xhigh"
    setModel={() => { }} setEffort={effort => { cleared = effort; }} />);

  fireEvent.change(screen.getByRole('combobox', { name: 'Model' }), { target: { value: 'TEST-fast' } });

  expect(cleared).toBe('');
});
