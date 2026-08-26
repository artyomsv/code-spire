import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SettingsContextProviders from './SettingsContextProviders';
import * as api from '../api';

const renderPage = () => render(<MemoryRouter><SettingsContextProviders /></MemoryRouter>);

/**
 * The Auth select must never be left holding a value the new type rejects. Jira/Confluence permit
 * `basic` (the form's default) but GitHub/GitLab issue providers are bearer-only, so switching the
 * Type select has to coerce Auth as a direct consequence — not leave it to a later effect.
 */
describe('SettingsContextProviders — add-provider form', () => {
  beforeEach(() => {
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([]);
  });

  it('coerces auth to bearer when switching to a bearer-only type', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /add provider/i }));

    expect(await screen.findByRole('combobox', { name: /^auth$/i })).toHaveTextContent(/basic/i);

    fireEvent.click(screen.getByRole('combobox', { name: /^type$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'github-issues' }));

    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: /^auth$/i })).toHaveTextContent(/bearer/i),
    );
    expect(screen.getByRole('combobox', { name: /^auth$/i })).not.toHaveTextContent(/basic/i);
  });

  it('leaves auth at bearer-only and does not offer basic for gitlab-issues', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /add provider/i }));

    fireEvent.click(screen.getByRole('combobox', { name: /^type$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'gitlab-issues' }));

    fireEvent.click(await screen.findByRole('combobox', { name: /^auth$/i }));
    expect(screen.getByRole('listbox')).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /^basic/i })).not.toBeInTheDocument();
  });

  /**
   * The row rendered an Enabled/Disabled pill while the form posted `initial.enabled` straight back,
   * so the state was visible but unreachable — a provider could only be disabled through the API.
   */
  it('sends enabled=false when the Enabled box is unchecked on an existing provider', async () => {
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([
      {
        id: 'ctx-1',
        name: 'Acme Jira',
        type: 'jira',
        baseUrl: 'https://acme.example.invalid',
        authKind: 'basic',
        username: 'bot@example.invalid',
        projectKeys: 'ACME',
        enabled: true,
        isDefault: false,
        hasSecret: true,
        createdAt: '2026-07-31T00:00:00Z',
        lastCheckAt: null,
        lastCheckOk: null,
        lastCheckError: null,
      },
    ] as never);
    const update = vi.spyOn(api, 'updateContextProvider').mockResolvedValue(undefined as never);

    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /edit/i }));

    const box = await screen.findByRole('checkbox', { name: /enabled/i });
    expect(box).toBeChecked();
    fireEvent.click(box);
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }));

    await waitFor(() => expect(update).toHaveBeenCalled());
    expect(update.mock.calls[0][1]).toMatchObject({ enabled: false });
  });

  it('leaves auth alone when switching between types that permit the same kinds', async () => {
    // Jira and Confluence both allow basic and bearer, so a switch between them must not coerce.
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /add provider/i }));

    expect(await screen.findByRole('combobox', { name: /^auth$/i })).toHaveTextContent(/basic/i);

    fireEvent.click(screen.getByRole('combobox', { name: /^type$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'confluence' }));

    expect(screen.getByRole('combobox', { name: /^auth$/i })).toHaveTextContent(/basic/i);
  });

  it('offers the repository code provider type and forces bearer auth', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /add provider/i }));

    fireEvent.click(screen.getByRole('combobox', { name: /^type$/i }));
    fireEvent.click(await screen.findByRole('option', { name: 'Repository code' }));

    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: /^auth$/i })).toHaveTextContent(/bearer/i),
    );
    // Bearer-only types must not offer the account-email field — sending one would be silently
    // ignored by the API and mislead the operator into thinking it mattered.
    expect(screen.queryByLabelText(/account email/i)).not.toBeInTheDocument();
  });

  /**
   * The blank-secret-on-edit rule: sending secret: '' on save would wipe the stored token. This must
   * hold for every type, including a brand-new one — confirm 'code' goes through the same path rather
   * than around it.
   */
  it('does not send an empty secret when editing an existing code provider', async () => {
    vi.spyOn(api, 'fetchContextProviders').mockResolvedValue([
      {
        id: 'ctx-code-1',
        name: 'Acme repository code',
        type: 'code',
        baseUrl: 'https://api.github.com',
        authKind: 'bearer',
        username: null,
        projectKeys: 'src/main/',
        enabled: true,
        isDefault: false,
        hasSecret: true,
        createdAt: '2026-08-01T00:00:00Z',
        lastCheckAt: null,
        lastCheckOk: null,
        lastCheckError: null,
      },
    ] as never);
    const update = vi.spyOn(api, 'updateContextProvider').mockResolvedValue(undefined as never);

    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: /edit/i }));

    const baseUrl = await screen.findByDisplayValue('https://api.github.com');
    fireEvent.change(baseUrl, { target: { value: 'https://github.example.invalid' } });
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }));

    await waitFor(() => expect(update).toHaveBeenCalled());
    expect(update.mock.calls[0][1].secret).toBeUndefined();
    expect(update.mock.calls[0][1]).toMatchObject({ baseUrl: 'https://github.example.invalid' });
  });
});
