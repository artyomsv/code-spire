import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsProviders from './SettingsProviders';
import * as api from '../api';

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
  workspace: 'acme',
  authKind: 'bearer',
  authUsername: null,
  hasSecret: true,
  botAccountId: 'acme-bot',
  enabled: true,
  authors: [],
  conversationLevel: null,
  createdAt: '2026-07-31T00:00:00Z',
  lastCheckAt: null,
  lastCheckOk: null,
  lastCheckError: null,
};

/**
 * "Add provider" names three different controls (the header icon, the empty-state button and the
 * modal's submit), so every form interaction is scoped to the dialog rather than the page.
 */
async function openAddForm(): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: /add provider/i }));
  return await screen.findByRole('dialog');
}

async function openFilledAddForm(): Promise<HTMLElement> {
  const dialog = await openAddForm();
  const form = within(dialog);
  fireEvent.change(form.getByLabelText('Name'), { target: { value: 'Acme' } });
  fireEvent.change(form.getByLabelText('Workspace'), { target: { value: 'acme' } });
  return dialog;
}

const submit = (dialog: HTMLElement) =>
  fireEvent.click(within(dialog).getByRole('button', { name: /^(add provider|save changes)$/i }));

const typeSecret = (dialog: HTMLElement) =>
  fireEvent.change(within(dialog).getByLabelText(/secret \/ token/i), {
    target: { value: 'placeholder-token' },
  });

describe('SettingsProviders — provider form', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchProviders').mockResolvedValue([]);
    vi.spyOn(api, 'checkProvider').mockResolvedValue({ ok: true, account: 'acme-bot' } as never);
  });

  /**
   * The extracted-helper tests prove the parsing rules; nothing proved the form applies them. A
   * submit that reaches the API with a blank workspace becomes a bad registry row whose failure
   * only surfaces later, during a real review, as an SCM error nobody can trace back.
   */
  it('refuses to submit without the required fields and does not call the API', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(undefined as never);
    renderPage();

    submit(await openAddForm());

    expect(await screen.findByText(/name, base url and workspace are required/i)).toBeInTheDocument();
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

    fireEvent.click(form.getByRole('combobox', { name: /^type$/i }));
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

    fireEvent.click(form.getByRole('combobox', { name: /^type$/i }));
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

    fireEvent.click(form.getByRole('combobox', { name: /^type$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'github' }));

    await waitFor(() =>
      expect(form.getByRole('combobox', { name: /auth kind/i })).toHaveTextContent(/bearer/i),
    );
    expect(form.getByRole('combobox', { name: /auth kind/i })).toBeDisabled();
  });

  /**
   * Typing an allowlist entry and pressing Save without pressing Add is the obvious operator
   * mistake; the form flushes the draft rather than dropping it silently.
   */
  it('includes an author typed but not added when the form is submitted', async () => {
    const create = vi.spyOn(api, 'createProvider').mockResolvedValue(undefined as never);
    renderPage();
    const dialog = await openFilledAddForm();
    typeSecret(dialog);
    // Under bearer auth the basic-auth Username field is hidden, so this placeholder is unambiguous.
    fireEvent.change(within(dialog).getByPlaceholderText('username'), { target: { value: 'acme-dev' } });
    submit(dialog);

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create.mock.calls[0][0].authors).toEqual(['acme-dev']);
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
    expect(await screen.findByText(/not checked/i)).toBeInTheDocument();
  });
});
