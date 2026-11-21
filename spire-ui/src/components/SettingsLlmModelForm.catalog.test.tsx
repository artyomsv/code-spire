import { describe, it, expect, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import { renderWithQuery } from '../test/queryWrapper';
import { catalogModel } from '../test/catalogFixtures';
import { catalogAsModel } from '../llmCatalog';
import type { CatalogModelView } from '../api';
import SettingsLlmModelForm from './SettingsLlmModelForm';

/** A catalog model with one price of $1 per 1M (100,000 millicents), one not-billed type and its own api dialect. */
const MODEL = catalogModel({
  provider: 'openai',
  api: { outputTokenParam: 'MAX_COMPLETION_TOKENS', temperature: false, reasoningEffort: 'low' },
  pricing: {
    input: 100_000, output: 200_000, cacheRead: null, cacheWrite: null, cacheWrite1h: 300_000, reasoning: null,
    notBilled: ['CACHE_WRITE'], sourceUrl: 'https://example.invalid/pricing', observedAt: '1970-01-01',
  },
  lifecycle: { status: 'DEPRECATED', deprecationDate: '1970-01-01', retirementDate: null, successor: 'TEST-cat-b' },
});

function open(model: CatalogModelView = MODEL, onClose = vi.fn()) {
  renderWithQuery(<SettingsLlmModelForm initial={catalogAsModel(model)} catalog={model} onClose={onClose}
    onSaved={vi.fn()} />);
  return screen.getByRole('dialog', { name: model.displayName });
}

/** The definition next to a term in the Catalog details list. */
function detail(dialog: HTMLElement, term: string): HTMLElement {
  const details = within(dialog).getByRole('region', { name: 'Catalog details' });
  return within(details).getByText(term, { selector: 'dt' }).nextElementSibling as HTMLElement;
}

describe('SettingsLlmModelForm — a catalog model', () => {
  it('opens the same dialog read-only: every control disabled, no Save, Close closes', () => {
    const onClose = vi.fn();
    const dialog = open(MODEL, onClose);
    const controls = [...within(dialog).getAllByRole('textbox'), ...within(dialog).getAllByRole('checkbox'),
      ...within(dialog).getAllByRole('combobox')];

    expect(controls.length).toBeGreaterThan(5);
    for (const control of controls) expect(control).toBeDisabled();
    expect(within(dialog).queryByRole('button', { name: /save|add model/i })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('note')).toHaveTextContent('changes only through a catalog update');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('fills the form from the entry: names, one price, one not-billed box, the api dialect', () => {
    const dialog = open();

    expect(within(dialog).getByLabelText(/^model name/i)).toHaveValue('TEST-cat-a');
    expect(within(dialog).getByLabelText(/^label/i)).toHaveValue('TEST catalog model');
    expect(within(dialog).getByLabelText(/^input rate/i)).toHaveValue('1');
    // An absent price and a not-billed type say so in words; no example price stands in an empty field.
    expect(within(dialog).getByLabelText(/^reasoning rate/i)).toHaveValue('not priced');
    expect(within(dialog).getByLabelText(/^cache write rate/i)).toHaveValue('not billed');
    const rates = within(dialog).getAllByLabelText(/rate \$ \/ 1M tokens/);
    expect(rates).toHaveLength(5);
    // No field carries an example value: not a rate, not the names, not the extra params (which a catalog model has
    // none of, so that field is left out).
    for (const field of within(dialog).getAllByRole('textbox')) expect(field).not.toHaveAttribute('placeholder');
    expect(within(dialog).queryByLabelText(/^extra params/i)).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText('Cache write: the vendor does not bill this')).toBeChecked();
    expect(within(dialog).getByLabelText('Input: the vendor does not bill this')).not.toBeChecked();
    expect(within(dialog).getByRole('combobox', { name: /output token limit/i })).toHaveTextContent('max_completion_tokens');
    expect(within(dialog).getByRole('combobox', { name: /reasoning effort/i })).toHaveTextContent('low');
    expect(within(dialog).getByLabelText(/accepts a custom temperature/i)).not.toBeChecked();
  });

  it('shows the catalog details the form has no field for', () => {
    const dialog = open();

    expect(detail(dialog, 'Lifecycle')).toHaveTextContent('deprecated · deprecated 1970-01-01 · successor TEST-cat-b');
    expect(detail(dialog, 'Context window')).toHaveTextContent('1');
    expect(detail(dialog, 'Max output tokens')).toHaveTextContent('1');
    expect(detail(dialog, 'Capabilities')).toHaveTextContent('Tools, Structured output, Prompt cache');
    expect(detail(dialog, 'Cache write (1 hour)')).toHaveTextContent('/1M · recorded, not charged');
    expect(within(detail(dialog, 'Prices from')).getByRole('link')).toHaveAttribute('href', 'https://example.invalid/pricing');
    expect(detail(dialog, 'Prices from')).toHaveTextContent('read 1970-01-01');
  });

  it('the editable form keeps its example placeholders and blank fields', () => {
    renderWithQuery(<SettingsLlmModelForm initial={null} onClose={vi.fn()} onSaved={vi.fn()} />);
    const dialog = screen.getByRole('dialog', { name: 'Add model' });

    expect(within(dialog).getByLabelText(/^input rate/i)).toHaveAttribute('placeholder');
    expect(within(dialog).getByLabelText(/^extra params/i)).toHaveAttribute('placeholder');
    expect(within(dialog).getByLabelText(/^reasoning rate/i)).toHaveValue('');
    expect(within(dialog).getByLabelText(/^reasoning rate/i)).toBeEnabled();
  });

  it('shows a dash for a value the catalog does not state', () => {
    const dialog = open(catalogModel({ provider: 'openai', contextWindow: null, capabilities: null, lifecycle: null, api: null }));

    expect(detail(dialog, 'Context window')).toHaveTextContent('—');
    expect(detail(dialog, 'Capabilities')).toHaveTextContent('—');
    expect(detail(dialog, 'Lifecycle')).toHaveTextContent('—');
    expect(detail(dialog, 'Cache write (1 hour)')).toHaveTextContent('not listed');
    expect(within(dialog).getByRole('combobox', { name: /output token limit/i })).toHaveTextContent('max_tokens');
  });
});
