import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import * as api from '../api';
import { catalogRow, catalogView } from '../test/catalogFixtures';
import { manualModel, renderLlmSettings as renderPage, rowOf, stubLlmSettingsReads } from '../test/llmSettingsFixtures';

beforeEach(stubLlmSettingsReads);

/** "Add model" names both the header icon and the modal's submit: open it before the submit exists. */
async function openAddModelForm(): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: /add model/i }));
  return await screen.findByRole('dialog');
}

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
    expect(confirm).toHaveTextContent('The catalog model with the same name takes its place, unless another manual model answers to one of its names');
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

describe('SettingsLlmProviders — a failed catalog read', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchLlmProviders').mockResolvedValue([]);
    vi.spyOn(api, 'checkLlmProvider').mockResolvedValue({ ok: true, detail: null });
  });

  it('says the catalog failed to load while the models still show, and retries', async () => {
    vi.spyOn(api, 'fetchLlmModels').mockResolvedValue([manualModel()]);
    const catalog = vi.spyOn(api, 'fetchLlmCatalog').mockRejectedValue(new Error('TEST-catalog down'));
    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the model catalog: TEST-catalog down');
    expect(await rowOf('TEST-manual')).toBeInTheDocument();
    catalog.mockResolvedValue(catalogView({ rows: [catalogRow()] }));
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading the catalog' }));

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });
});
