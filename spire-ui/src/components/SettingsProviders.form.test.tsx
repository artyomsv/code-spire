import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsProviders from './SettingsProviders';
import * as api from '../api';
import * as repositories from './repositories/repositoriesApi';

const renderPage = () =>
  render(
    <MemoryRouter>
      <SettingsProviders />
    </MemoryRouter>,
  );

const existing: api.ProviderView = {
  id: 'prov-1',
  name: 'Acme Bitbucket',
  type: 'bitbucket-cloud',
  baseUrl: 'https://api.bitbucket.org/2.0',
  authKind: 'bearer',
  authUsername: null,
  hasSecret: true,
  botAccountId: 'acme-bot',
  enabled: true,
  authors: [],
  conversationLevel: null,
  role: 'REVIEWER',
  botUsername: null,
  createdAt: '2026-07-31T00:00:00Z',
  lastCheckAt: null,
  lastCheckOk: null,
  lastCheckError: null,
};

/**
 * The page's two "Add account" controls (the header icon and the empty-state button) and the
 * modal's own submit all read as add buttons, so every form interaction is scoped to the dialog
 * rather than the page.
 */
async function openAddForm(): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: /add account/i }));
  return await screen.findByRole('dialog');
}

async function openFilledAddForm(): Promise<HTMLElement> {
  const dialog = await openAddForm();
  const form = within(dialog);
  fireEvent.change(form.getByLabelText('Name'), { target: { value: 'Acme' } });
  return dialog;
}

const submit = (dialog: HTMLElement) =>
  fireEvent.click(within(dialog).getByRole('button', { name: /^(add account|save changes)$/i }));

const typeSecret = (dialog: HTMLElement) =>
  fireEvent.change(within(dialog).getByLabelText(/secret \/ token/i), {
    target: { value: 'placeholder-token' },
  });

describe('SettingsProviders — provider form', () => {
  it('validates an account-less token against an explicit same-origin repository', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(existing);
    const repository: repositories.Repository = { id: 'TEST-selected', scmType: 'bitbucket-cloud',
      forgeOrigin: 'https://api.bitbucket.org', workspace: 'TEST-team', slug: 'TEST-project',
      enabled: true, revision: 1, reviewer: null, factory: null };
    vi.spyOn(repositories, 'fetchRepositories').mockResolvedValue([repository,
      { ...repository, id: 'TEST-other-origin', forgeOrigin: 'https://other.example.test', slug: 'TEST-other-origin' },
      { ...repository, id: 'TEST-other-kind', scmType: 'github', slug: 'TEST-other-kind' }]);
    renderPage();
    const dialog = await openFilledAddForm();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Choose validation repository' }));
    const selector = await within(dialog).findByRole('combobox', { name: 'Validation repository' });
    expect(within(selector).getAllByRole('option')).toHaveLength(2);
    fireEvent.change(selector, { target: { value: repository.id } });
    typeSecret(dialog);
    submit(dialog);
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.not.objectContaining({ workspace: expect.anything() }), repository.id));
  });

  it('clears the validation repository when the account origin changes', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(existing);
    vi.spyOn(repositories, 'fetchRepositories').mockResolvedValue([{ id: 'TEST-selected', scmType: 'bitbucket-cloud',
      forgeOrigin: 'https://api.bitbucket.org', workspace: 'TEST-team', slug: 'TEST-project',
      enabled: true, revision: 1, reviewer: null, factory: null }]);
    renderPage();
    const dialog = await openFilledAddForm();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Choose validation repository' }));
    fireEvent.change(await within(dialog).findByRole('combobox', { name: 'Validation repository' }), { target: { value: 'TEST-selected' } });
    fireEvent.change(within(dialog).getByLabelText('Base URL'), { target: { value: 'https://other.example.test' } });
    typeSecret(dialog);
    submit(dialog);
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.objectContaining({ baseUrl: 'https://other.example.test' })));
  });
  it('does not offer workspace on an account', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(existing);
    renderPage();
    const dialog = await openFilledAddForm();
    expect(within(dialog).queryByRole('textbox', { name: /^workspace$/i })).not.toBeInTheDocument();
    typeSecret(dialog);
    submit(dialog);
    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0]).not.toHaveProperty('workspace');
  });
  beforeEach(() => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
    vi.spyOn(api, 'checkProvider').mockResolvedValue({ ok: true, account: 'acme-bot' } as never);
  });

  /**
   * The extracted-helper tests prove the parsing rules; nothing proved the form applies them. A
   * submit that reaches the API with a blank workspace becomes a bad registry row whose failure
   * only surfaces later, during a real review, as an SCM error nobody can trace back.
   */
  it('registers an Atlassian account without forge role or workspace fields', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(existing);
    renderPage();
    const dialog = await openAddForm();
    const form = within(dialog);
    fireEvent.click(form.getByRole('combobox', { name: 'Kind' }));
    fireEvent.click(await screen.findByRole('option', { name: 'atlassian' }));
    expect(form.queryByRole('combobox', { name: 'Role' })).not.toBeInTheDocument();
    expect(form.queryByLabelText('Workspace')).not.toBeInTheDocument();
    expect(form.getByRole('combobox', { name: 'Auth kind' })).toHaveTextContent('basic');
    fireEvent.change(form.getByLabelText('Name'), { target: { value: 'Site bot' } });
    fireEvent.change(form.getByLabelText('Base URL'), { target: { value: 'https://site.atlassian.net' } });
    fireEvent.change(form.getByLabelText('Username'), { target: { value: 'bot@example.test' } });
    typeSecret(dialog);
    submit(dialog);
    await waitFor(() => expect(create).toHaveBeenCalledWith(expect.objectContaining({ type: 'atlassian', role: 'CONTEXT', authKind: 'basic' })));
  });

  it('refuses to submit without the required fields and does not call the API', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(undefined as never);
    renderPage();

    submit(await openAddForm());

    expect(await screen.findByText(/name and base url are required/i)).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  it('requires a username when the auth kind is basic', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(undefined as never);
    renderPage();
    const dialog = await openFilledAddForm();

    fireEvent.click(within(dialog).getByRole('combobox', { name: /auth kind/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'basic' }));
    typeSecret(dialog);
    submit(dialog);

    expect(await screen.findByText(/username is required for basic auth/i)).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  it('requires a secret when adding, since there is no stored token to fall back on', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(undefined as never);
    renderPage();

    submit(await openFilledAddForm());

    expect(await screen.findByText(/a secret \/ token is required/i)).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  /**
   * The security-relevant half of the same rule: editing must accept a blank secret AND must omit
   * the key entirely from the payload. Sending `secret: ''` would overwrite the stored token with
   * an empty one — the field's own hint promises "leave blank to keep it".
   */
  it('omits the secret key entirely when an edit leaves the field blank', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([existing]);
    const update = vi.spyOn(api, 'updateProvider').mockResolvedValue(undefined as never);
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /edit/i }));
    submit(await screen.findByRole('dialog'));

    await waitFor(() => expect(update).toHaveBeenCalled());
    expect(update.mock.calls[0][1]).not.toHaveProperty('secret');
  });

  it('swaps the base URL to the new type default when it has not been customised', async () => {
    renderPage();
    const dialog = await openAddForm();
    const form = within(dialog);
    expect(form.getByLabelText(/base url/i)).toHaveValue('https://api.bitbucket.org/2.0');

    fireEvent.click(form.getByRole('combobox', { name: /^kind$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'github' }));

    await waitFor(() => expect(form.getByLabelText(/base url/i)).toHaveValue('https://api.github.com'));
  });

  /**
   * A self-hosted instance's URL is the whole reason the field is editable — a type switch that
   * silently discarded it would point the review at the wrong host.
   */
  it('preserves a customised base URL across a type switch', async () => {
    renderPage();
    const dialog = await openAddForm();
    const form = within(dialog);
    fireEvent.change(form.getByLabelText(/base url/i), {
      target: { value: 'https://git.example.invalid/api/v4' },
    });

    fireEvent.click(form.getByRole('combobox', { name: /^kind$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'gitlab' }));

    expect(form.getByLabelText(/base url/i)).toHaveValue('https://git.example.invalid/api/v4');
  });

  /**
   * GitHub and GitLab are bearer-only, so `basic` must become both unselected and unofferable —
   * leaving it selectable lets an operator save a combination the provider will reject.
   */
  it('coerces auth to bearer and stops offering basic for a bearer-only type', async () => {
    renderPage();
    const dialog = await openAddForm();
    const form = within(dialog);

    fireEvent.click(form.getByRole('combobox', { name: /auth kind/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'basic' }));
    expect(form.getByRole('combobox', { name: /auth kind/i })).toHaveTextContent(/basic/i);

    fireEvent.click(form.getByRole('combobox', { name: /^kind$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'github' }));

    await waitFor(() =>
      expect(form.getByRole('combobox', { name: /auth kind/i })).toHaveTextContent(/bearer/i),
    );
    expect(form.getByRole('combobox', { name: /auth kind/i })).toBeDisabled();
  });

  /**
   * `authUsername` is meaningless under bearer auth; sending a stale one would persist a field the
   * form no longer shows, so it is explicitly nulled rather than left at its last value.
   */
  it('nulls authUsername when the auth kind is not basic', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(undefined as never);
    renderPage();
    const dialog = await openFilledAddForm();
    typeSecret(dialog);
    submit(dialog);

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0].authUsername).toBeNull();
  });

  it('surfaces a create failure instead of closing the form', async () => {
    vi.spyOn(api, 'createProvider').mockRejectedValue(new Error('The stored credential was rejected.'));
    renderPage();
    const dialog = await openFilledAddForm();
    typeSecret(dialog);
    submit(dialog);

    expect(await screen.findByText(/the stored credential was rejected/i)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  /**
   * The form can express the FACTORY role, because the 409 from POST /api/runs sends the operator
   * here. Asserted on what reaches the API rather than on the control: a form that omits the field
   * sends a payload the server reads as REVIEWER, and both look identical on screen.
   *
   * Both reviewer-only fields are filled BEFORE the switch, because hiding a field and clearing it
   * from the payload are different things. A form that only stops rendering them still submits the
   * values its state kept, and the server would store an allowlist and a conversation level on an
   * account whose screen shows neither.
   */
  it('sends the FACTORY role the machine account needs, and no reviewer field with it', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(existing);
    renderPage();
    const dialog = await openFilledAddForm();
    typeSecret(dialog);

    fireEvent.click(within(dialog).getByRole('combobox', { name: /conversation level/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'Explain' }));
    fireEvent.change(within(dialog).getByPlaceholderText('stable user id'), { target: { value: '3218389' } });

    fireEvent.click(within(dialog).getByRole('combobox', { name: /^role$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'Factory' }));
    submit(dialog);

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0].role).toBe('FACTORY');
    expect(create.mock.calls[0][0].authors).toEqual([]);
    expect(create.mock.calls[0][0].conversationLevel).toBeUndefined();
  });

  it('defaults a new account to REVIEWER and sends that explicitly', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(existing);
    renderPage();
    const dialog = await openFilledAddForm();
    typeSecret(dialog);
    submit(dialog);

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0].role).toBe('REVIEWER');
  });

  /** A role is fixed at registration; the edit form shows it and sends it back unchanged. */
  it('shows the stored role read-only on edit and sends it back, never another', async () => {
    const factory: api.ProviderView = { ...existing, id: 'prov-2', name: 'Acme Factory', role: 'FACTORY' };
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([factory]);
    const update = vi.spyOn(api, 'updateProvider').mockResolvedValue(factory);
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /^edit$/i }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByRole('combobox', { name: /^role$/i })).not.toBeInTheDocument();
    expect(within(dialog).getByText('Factory')).toBeInTheDocument();
    expect(within(dialog).getByText(/set at registration/i)).toBeInTheDocument();
    submit(dialog);

    await waitFor(() => expect(update).toHaveBeenCalled());
    expect(update.mock.calls[0][1].role).toBe('FACTORY');
  });

  /** The allowlist and the conversation level are the reviewer's; a factory account has neither. */
  it('hides the reviewer-only fields once Factory is chosen', async () => {
    renderPage();
    const dialog = await openFilledAddForm();
    expect(within(dialog).getByText(/may command this bot/i)).toBeInTheDocument();
    expect(within(dialog).getByRole('combobox', { name: /conversation level/i })).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole('combobox', { name: /^role$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'Factory' }));

    expect(within(dialog).queryByText(/may command this bot/i)).not.toBeInTheDocument();
    expect(within(dialog).queryByRole('combobox', { name: /conversation level/i })).not.toBeInTheDocument();
    expect(within(dialog).getByText(/must resolve to a login/i)).toBeInTheDocument();
  });

  /**
   * /fix matches the stable id only; a field that says "username" leads to a list /fix refuses.
   * This also covers the flush: typing an allowlist entry and pressing Save without pressing Add is
   * the obvious operator mistake, and the form must not drop the draft silently.
   */
  it('asks for a stable user id in the allowlist, and flushes a typed one on submit', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(existing);
    renderPage();
    const dialog = await openFilledAddForm();
    typeSecret(dialog);

    const field = within(dialog).getByPlaceholderText('stable user id');
    fireEvent.change(field, { target: { value: '3218389' } });
    submit(dialog);

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0].authors).toEqual(['3218389']);
  });
});

/**
 * A disabled provider is intentionally inactive and may hold a deliberately revoked token, so
 * contacting the SCM for it on page load is both wasteful and misleading — it would render a red
 * Failed cell for a provider nobody asked to be working.
 */
describe('SettingsProviders — connectivity on load', () => {
  it('checks enabled providers only, leaving a disabled one unchecked', async () => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([
      existing,
      { ...existing, id: 'prov-2', name: 'Dormant', enabled: false },
    ]);
    const check = vi.spyOn(api, 'checkProvider').mockResolvedValue({ ok: true, account: 'acme-bot' } as never);

    renderPage();

    await waitFor(() => expect(check).toHaveBeenCalledWith('prov-1'));
    expect(check).not.toHaveBeenCalledWith('prov-2');
    // The standing is an icon on this table; its word heads the accessible name, and the name
    // says the action too — it is a button, and the disabled row is the one still worth pressing.
    expect(await screen.findByRole('button', { name: 'Not checked — check the connection' })).toBeInTheDocument();
  });
});
