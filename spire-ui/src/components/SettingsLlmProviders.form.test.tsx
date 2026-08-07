import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsLlmProviders from './SettingsLlmProviders';
import * as api from '../api';

const renderPage = () =>
  render(
    <MemoryRouter>
      <SettingsLlmProviders />
    </MemoryRouter>,
  );

/** "Add model" names both the header icon and the modal's submit — scope form interactions to the
 *  dialog, and open it before the submit button exists so the opening click stays unambiguous. */
async function openAddModelForm(): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: /add model/i }));
  return await screen.findByRole('dialog');
}

function fillNameAndLabel(dialog: HTMLElement) {
  const form = within(dialog);
  fireEvent.change(form.getByLabelText(/^model name/i), { target: { value: 'TEST-MODEL' } });
  fireEvent.change(form.getByLabelText(/^label/i), { target: { value: 'TEST label' } });
}

const submitModel = (dialog: HTMLElement) =>
  fireEvent.click(within(dialog).getByRole('button', { name: /^add model$/i }));

describe('SettingsLlmProviders — model form', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchLlmProviders').mockResolvedValue([]);
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([]);
  });

  /**
   * One third of the accounting bug lived here: a blank price field became `Number('') || 0`, which
   * the server accepted as a valid free model. A blank field is now an error, and zero is only
   * reachable by choosing self-hosted (UNMETERED).
   */
  it('refuses to submit a metered model with a blank rate instead of sending zero', async () => {
    const createLlmModel = vi.spyOn(api, 'createLlmModel');
    renderPage();
    const dialog = await openAddModelForm();
    fillNameAndLabel(dialog);
    // Input rate left blank on purpose.
    submitModel(dialog);

    expect(await screen.findByText(/rate is required/i)).toBeInTheDocument();
    expect(createLlmModel).not.toHaveBeenCalled();
  });

  it('sends no rates at all when the model is marked self-hosted', async () => {
    const createLlmModel = vi.spyOn(api, 'createLlmModel').mockResolvedValue({} as never);
    renderPage();
    const dialog = await openAddModelForm();
    fillNameAndLabel(dialog);
    fireEvent.click(within(dialog).getByLabelText(/self-hosted/i));
    submitModel(dialog);

    await waitFor(() =>
      expect(createLlmModel).toHaveBeenCalledWith(
        expect.objectContaining({ pricingMode: 'UNMETERED', rates: {} }),
      ),
    );
  });

  it('sends an optional rate only when it was filled in', async () => {
    const createLlmModel = vi.spyOn(api, 'createLlmModel').mockResolvedValue({} as never);
    renderPage();
    const dialog = await openAddModelForm();
    const form = within(dialog);
    fillNameAndLabel(dialog);
    fireEvent.change(form.getByLabelText(/^input rate/i), { target: { value: '2.50' } });
    fireEvent.change(form.getByLabelText(/^output rate/i), { target: { value: '10' } });
    // Cached input / cache write / reasoning left blank — optional dimensions this model doesn't bill.
    submitModel(dialog);

    await waitFor(() => expect(createLlmModel).toHaveBeenCalled());
    const sent = createLlmModel.mock.calls[0][0];
    expect(Object.keys(sent.rates).sort()).toEqual(['INPUT', 'OUTPUT']);
  });

  it('converts every filled rate from dollars to millicents per million tokens', async () => {
    const createLlmModel = vi.spyOn(api, 'createLlmModel').mockResolvedValue({} as never);
    renderPage();
    const dialog = await openAddModelForm();
    const form = within(dialog);
    fillNameAndLabel(dialog);
    fireEvent.change(form.getByLabelText(/^input rate/i), { target: { value: '2.50' } });
    fireEvent.change(form.getByLabelText(/^cached input rate/i), { target: { value: '0.30' } });
    fireEvent.change(form.getByLabelText(/^output rate/i), { target: { value: '10' } });
    submitModel(dialog);

    await waitFor(() => expect(createLlmModel).toHaveBeenCalled());
    const sent = createLlmModel.mock.calls[0][0];
    expect(sent.rates).toEqual({ INPUT: 250_000, CACHED_INPUT: 30_000, OUTPUT: 1_000_000 });
  });

  it('shows a model marked self-hosted in the catalog table as "Self-hosted", never as a price', async () => {
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([
      {
        id: 'model-1',
        type: 'openai',
        name: 'TEST-SELF-HOSTED-MODEL',
        label: 'TEST self-hosted',
        pricingMode: 'UNMETERED',
        rates: {},
        outputTokenParam: 'MAX_TOKENS',
        supportsTemperature: true,
        reasoningEffort: null,
        extraParams: {},
        enabled: true,
        createdAt: '2026-08-06T00:00:00Z',
      },
    ]);
    renderPage();

    expect(await screen.findByText('Self-hosted')).toBeInTheDocument();
    expect(screen.queryByText('$0.00')).not.toBeInTheDocument();
  });
});
