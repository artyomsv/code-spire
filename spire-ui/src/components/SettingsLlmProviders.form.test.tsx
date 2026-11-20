import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { act } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { createQueryClient } from '../queryClient';
import { invalidateLive } from '../liveCache';
import { queryWrapper } from '../test/queryWrapper';
import SettingsLlmProviders from './SettingsLlmProviders';
import * as api from '../api';
import { catalogRow, catalogView } from '../test/catalogFixtures';

// Every test reads the catalog; one with no rows unless a test says otherwise.
beforeEach(() => {
  vi.spyOn(api, 'fetchLlmCatalog').mockResolvedValue(catalogView());
});

const renderPage = (wrapper = queryWrapper()) =>
  render(
    <MemoryRouter>
      <SettingsLlmProviders />
    </MemoryRouter>,
    { wrapper },
  );

const provider = (over: Partial<api.LlmProviderView> = {}): api.LlmProviderView => ({
  id: 'TEST-provider-1', name: 'TEST-provider', type: 'openai', baseUrl: 'https://llm.example.test/v1',
  model: 'TEST-MODEL', temperature: 0.2, maxTokens: null, hasApiKey: true, enabled: true, isDefault: true,
  createdAt: '1970-01-01T00:00:00Z', lastCheckAt: null, lastCheckOk: null, lastCheckError: null, ...over,
});

describe('SettingsLlmProviders — reads', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchLlmProviders').mockResolvedValue([provider(), provider({ id: 'TEST-provider-2', name: 'TEST-off', enabled: false, isDefault: false })]);
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([]);
    vi.spyOn(api, 'checkLlmProvider').mockResolvedValue({ ok: true, detail: null });
  });

  // A cached row may be out of date; it may be read, but not edited, until this visit's read answers.
  it('shows the providers at once when the page opens again, with Edit off until this visit\'s read answers', async () => {
    const wrapper = queryWrapper();
    const first = renderPage(wrapper);
    expect(await screen.findByText('TEST-provider')).toBeInTheDocument();
    first.unmount();
    vi.mocked(api.fetchLlmProviders).mockReturnValue(new Promise(() => {}));
    renderPage(wrapper);
    const row = screen.getByText('TEST-provider').closest('tr') as HTMLElement;
    expect(within(row).getByRole('button', { name: 'Edit' })).toBeDisabled();
  });

  // A re-read with the same list keeps the same data reference; the checks must still run for it.
  it('checks each enabled provider once per completed read', async () => {
    const client = createQueryClient();
    render(
      <MemoryRouter>
        <QueryClientProvider client={client}>
          <SettingsLlmProviders />
        </QueryClientProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(api.checkLlmProvider).toHaveBeenCalledTimes(1));
    await act(async () => { await invalidateLive(client, api.llmProvidersKey); });
    await waitFor(() => expect(api.checkLlmProvider).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.checkLlmProvider).mock.calls.map(([id]) => id)).toEqual(['TEST-provider-1', 'TEST-provider-1']);
  });
});

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

    expect(await screen.findByText(/needs a rate, or the mark saying this vendor does not bill it/i)).toBeInTheDocument();
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

  /**
   * Extra params is a raw pass-through textarea, so the form is the only thing standing between a typo
   * and a request body. Both of its rejections are covered here because they are different mistakes:
   * text that is not JSON at all, and JSON that is not an object (an array parses fine and would be
   * sent as one).
   */
  it('refuses malformed JSON in extra params instead of sending it', async () => {
    const createLlmModel = vi.spyOn(api, 'createLlmModel');
    renderPage();
    const dialog = await openAddModelForm();
    const form = within(dialog);
    fillNameAndLabel(dialog);
    fireEvent.change(form.getByLabelText(/^input rate/i), { target: { value: '2.50' } });
    fireEvent.change(form.getByLabelText(/^output rate/i), { target: { value: '10' } });
    fireEvent.change(form.getByLabelText(/^extra params/i), { target: { value: '{ not json' } });
    submitModel(dialog);

    expect(await screen.findByText(/extra params must be valid json/i)).toBeInTheDocument();
    expect(createLlmModel).not.toHaveBeenCalled();
  });

  it('refuses a JSON array in extra params, which parses but is not an object', async () => {
    const createLlmModel = vi.spyOn(api, 'createLlmModel');
    renderPage();
    const dialog = await openAddModelForm();
    const form = within(dialog);
    fillNameAndLabel(dialog);
    fireEvent.change(form.getByLabelText(/^input rate/i), { target: { value: '2.50' } });
    fireEvent.change(form.getByLabelText(/^output rate/i), { target: { value: '10' } });
    fireEvent.change(form.getByLabelText(/^extra params/i), { target: { value: '["service_tier"]' } });
    submitModel(dialog);

    expect(await screen.findByText(/must be a json object/i)).toBeInTheDocument();
    expect(createLlmModel).not.toHaveBeenCalled();
  });

  /**
   * A reasoning model needs `max_completion_tokens` AND rejects a custom temperature, so choosing the
   * first presets the second. Two settings that must agree, where only one is the operator's stated
   * intent — leaving them independent means a model saved with a dialect its API refuses.
   */
  it('unchecks the temperature toggle when the reasoning-model output cap is chosen', async () => {
    const createLlmModel = vi.spyOn(api, 'createLlmModel').mockResolvedValue({} as never);
    renderPage();
    const dialog = await openAddModelForm();
    const form = within(dialog);
    fillNameAndLabel(dialog);
    fireEvent.change(form.getByLabelText(/^input rate/i), { target: { value: '2.50' } });
    fireEvent.change(form.getByLabelText(/^output rate/i), { target: { value: '10' } });

    const temperature = form.getByLabelText(/accepts a custom temperature/i);
    expect(temperature).toBeChecked();
    // The dialect field is the project's own Select — a button plus a portalled listbox, not a native
    // <select> — so it is driven by opening it and clicking the option, and the listbox lands outside
    // the dialog in <body>.
    fireEvent.click(form.getByRole('combobox', { name: /output token limit/i }));
    fireEvent.click(await screen.findByRole('option', { name: /max_completion_tokens/i }));

    expect(temperature).not.toBeChecked();
    submitModel(dialog);
    await waitFor(() =>
      expect(createLlmModel).toHaveBeenCalledWith(
        expect.objectContaining({
          outputTokenParam: 'MAX_COMPLETION_TOKENS',
          supportsTemperature: false,
        }),
      ),
    );
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
        notBilled: [],
        outputTokenParam: 'MAX_TOKENS',
        supportsTemperature: true,
        reasoningEffort: null,
        extraParams: {},
        enabled: true,
        createdAt: '2026-08-06T00:00:00Z', source: 'MANUAL',
      },
    ]);
    renderPage();

    expect(await screen.findByText('Self-hosted')).toBeInTheDocument();
    expect(screen.queryByText('$0.00')).not.toBeInTheDocument();
  });
});


const manualModel = (over: Partial<api.LlmModelView> = {}): api.LlmModelView => ({
  id: 'TEST-manual-id', type: 'openai', name: 'TEST-manual', label: 'TEST manual', pricingMode: 'METERED',
  rates: { INPUT: 1, OUTPUT: 2 }, notBilled: [], outputTokenParam: 'MAX_TOKENS', supportsTemperature: true,
  reasoningEffort: null, extraParams: {}, enabled: true, createdAt: '1970-01-01T00:00:00Z', source: 'MANUAL', ...over,
});

const rowOf = async (text: string) => (await screen.findByText(text)).closest('tr') as HTMLElement;

describe('SettingsLlmProviders — catalog models', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchLlmProviders').mockResolvedValue([]);
    vi.spyOn(api, 'checkLlmProvider').mockResolvedValue({ ok: true, detail: null });
  });

  it('a shadowed catalog row says Ignored', async () => {
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([manualModel({ name: 'TEST-cat-a' })]);
    vi.spyOn(api, 'fetchLlmCatalog').mockResolvedValue(catalogView({ rows: [catalogRow({}, true)] }));
    renderPage();

    const catalogRowEl = await rowOf('Ignored · a manual model has this name');
    expect(within(catalogRowEl).getByText('Catalog')).toBeInTheDocument();
  });

  it('a manual row that shadows offers Use catalog', async () => {
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([manualModel({ name: 'TEST-cat-a' })]);
    vi.spyOn(api, 'fetchLlmCatalog').mockResolvedValue(catalogView({ rows: [catalogRow({}, true)] }));
    const remove = vi.spyOn(api, 'deleteLlmModel').mockResolvedValue(undefined);
    renderPage();

    const manualRowEl = await rowOf('Active · overrides catalog');
    await waitFor(() => expect(within(manualRowEl).getByRole('button', { name: 'Use catalog' })).toBeEnabled());
    fireEvent.click(within(manualRowEl).getByRole('button', { name: 'Use catalog' }));
    const confirm = (await screen.findByRole('heading', { name: /Use the catalog for/ })).closest('.modal') as HTMLElement;
    expect(confirm).toHaveTextContent('The catalog model with the same name takes its place.');
    fireEvent.click(within(confirm).getByRole('button', { name: 'Use catalog' }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith('TEST-manual-id'));
  });

  it('a catalog row has no Edit or Delete', async () => {
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([manualModel(), manualModel({
      id: null, name: 'TEST-cat-a', label: 'TEST catalog model', source: 'CATALOG', createdAt: null,
    })]);
    vi.spyOn(api, 'fetchLlmCatalog').mockResolvedValue(catalogView({ rows: [catalogRow()] }));
    renderPage();

    const catalogRowEl = (await screen.findByRole('button', { name: 'Catalog model TEST-cat-a' })).closest('tr') as HTMLElement;
    expect(within(catalogRowEl).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(within(catalogRowEl).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
    const manualRowEl = await rowOf('TEST-manual');
    expect(within(manualRowEl).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.getAllByText('TEST-cat-a')).toHaveLength(1);
  });

  it('a retired catalog row says Retired', async () => {
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([]);
    vi.spyOn(api, 'fetchLlmCatalog').mockResolvedValue(catalogView({ rows: [catalogRow({
      lifecycle: { status: 'RETIRED', deprecationDate: null, retirementDate: null, successor: null },
    })] }));
    renderPage();

    expect(within(await rowOf('Retired')).getByText('Catalog')).toBeInTheDocument();
  });

  it('opens a catalog row read-only', async () => {
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([]);
    vi.spyOn(api, 'fetchLlmCatalog').mockResolvedValue(catalogView({ rows: [catalogRow()] }));
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: 'Catalog model TEST-cat-a' }));

    const panel = await screen.findByRole('dialog', { name: 'TEST catalog model' });
    expect(within(panel).getByText('Prices from')).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('typing a catalog name shows the override notice', async () => {
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([]);
    vi.spyOn(api, 'fetchLlmCatalog').mockResolvedValue(catalogView({ rows: [catalogRow({ aliases: ['TEST-cat-a-dated'] })] }));
    renderPage();
    await screen.findByRole('button', { name: 'Catalog model TEST-cat-a' });
    const dialog = await openAddModelForm();

    fireEvent.change(within(dialog).getByLabelText(/^model name/i), { target: { value: 'TEST-other' } });
    expect(within(dialog).queryByText('This overrides the catalog model with the same name.')).not.toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText(/^model name/i), { target: { value: 'TEST-cat-a-dated' } });
    expect(within(dialog).getByText('This overrides the catalog model with the same name.')).toBeInTheDocument();
  });
});
