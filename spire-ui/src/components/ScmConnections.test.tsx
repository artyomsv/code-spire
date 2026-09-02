import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { ScmConnections } from './ScmConnections';
import * as api from '../api';

/**
 * Setting up the applications operators sign into (P4 / FR-11).
 *
 * <p>The rule with teeth is the blank secret. Sending an empty string on an edit would wipe a
 * working credential, and the failure would surface later as every operator's sign-in being refused
 * — with nothing on this screen looking different. The provider settings form carries the same rule
 * for the same reason.
 */

const APPS: api.ScmOAuthApp[] = [
  {
    providerType: 'github',
    webBaseUrl: null,
    apiBaseUrl: null,
    clientId: 'TEST-CLIENT-ID',
    hasSecret: true,
    connectable: true,
    redirectUri: 'https://spire.example.invalid/api/operator-connect/github/callback',
  },
  {
    providerType: 'gitlab',
    webBaseUrl: null,
    apiBaseUrl: null,
    clientId: '',
    hasSecret: false,
    connectable: true,
    redirectUri: 'https://spire.example.invalid/api/operator-connect/gitlab/callback',
  },
];

describe('ScmConnections', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(api, 'fetchScmOAuthApps').mockResolvedValue(APPS);
  });

  it('separates a platform that is set up from one that is not', async () => {
    render(<ScmConnections />);

    // Named the way the platform names itself, not by the internal provider type: this row is read
    // beside that platform’s own portal, and "bitbucket-cloud" appears nowhere in it.
    await waitFor(() => expect(screen.getByText('GitHub')).toBeTruthy());
    expect(screen.getByText('GitLab')).toBeTruthy();
    expect(screen.getByText('Configured')).toBeTruthy();
    expect(screen.getByText('Not set up')).toBeTruthy();
  });

  /**
   * The redirect address is the one value an admin cannot work out: it depends on how the
   * deployment is reached, and registering the wrong one fails at the platform with a message that
   * names nothing in this product.
   */
  it('shows the redirect address to register, ready to copy', async () => {
    render(<ScmConnections />);

    fireEvent.click((await screen.findAllByText('Edit'))[0]);

    expect(await screen.findByText('Redirect address to register')).toBeTruthy();
    expect(
      screen.getByText('https://spire.example.invalid/api/operator-connect/github/callback'),
    ).toBeTruthy();
    // Copied, never retyped. One wrong character fails on the platform with a message that names
    // nothing in this product, which is the same reason Settings -> Webhooks copies its payload URL.
    expect(screen.getByText('Copy')).toBeTruthy();
  });

  /**
   * The question the panel originally answered nowhere. An admin cannot start until they know the
   * application is one per platform, belongs to the account that owns the repositories, and is not
   * the bot credential they already set up.
   */
  it('answers whose account registers it, before showing the form', async () => {
    render(<ScmConnections />);

    expect(await screen.findByText(/once per platform/)).toBeTruthy();
    expect(screen.getByText(/not the bot/i)).toBeTruthy();
    // Read beside Settings -> Webhooks, which DOES need a public URL, so the difference has to be
    // stated rather than left to be inferred: a sign-in needs nothing inbound at all.
    expect(screen.getByText(/No tunnel needed/i)).toBeTruthy();

    fireEvent.click((await screen.findAllByText('Edit'))[0]);
    expect(await screen.findByText(/Register it under the account that owns the repositories/))
      .toBeTruthy();
    // Both cases, side by side. Repositories owned by one person are the common case for a small
    // deployment, and an answer that only named the organization left it looking unsupported.
    expect(screen.getByText('Shared repositories')).toBeTruthy();
    expect(screen.getByText('Your own repositories')).toBeTruthy();
    expect(screen.getByText(/your own account → Settings/)).toBeTruthy();
  });

  /**
   * The instructions are the feature. Registering an application means working in a portal where
   * every field is named differently -- GitLab calls the client id an Application ID, Bitbucket
   * calls it a Key -- and two platforms refuse to issue a secret at all unless a box is ticked.
   */
  it('walks through the platform’s own portal, step by step', async () => {
    render(<ScmConnections />);
    fireEvent.click((await screen.findAllByText('Edit'))[0]);

    expect(await screen.findByText(/On GitHub . one-off setup/)).toBeTruthy();
    expect(screen.getByText(/Authorization callback URL/)).toBeTruthy();
    expect(screen.getByText(/Generate a new client secret/)).toBeTruthy();
  });

  /** Blank means the hosted service everywhere, so the hint has to say what self-managed needs. */
  it('says what a self-managed install needs in each base field', async () => {
    render(<ScmConnections />);
    fireEvent.click((await screen.findAllByText('Edit'))[0]);

    expect(await screen.findByText(/api\/v3/)).toBeTruthy();
  });

  /**
   * Editing a configured platform without retyping the secret must KEEP it. Sending '' here is the
   * defect this test exists for: it would erase a credential nobody noticed was gone until every
   * operator's sign-in started failing.
   */
  it('keeps the stored secret when the field is left blank', async () => {
    const save = vi.spyOn(api, 'saveScmOAuthApp').mockResolvedValue(undefined);

    render(<ScmConnections />);
    fireEvent.click((await screen.findAllByText('Edit'))[0]);

    fireEvent.change(await screen.findByLabelText(/Sign-in base URL/), {
      target: { value: 'https://github.example.invalid' },
    });
    fireEvent.submit(document.querySelector('form.oauth-app-form') as HTMLFormElement);

    await waitFor(() =>
      expect(save).toHaveBeenCalledWith({
        providerType: 'github',
        clientId: 'TEST-CLIENT-ID',
        clientSecret: '',
        webBaseUrl: 'https://github.example.invalid',
        apiBaseUrl: '',
      }),
    );
  });

  /** A first setup has nothing to keep, so the secret is required rather than optional. */
  it('requires a secret the first time a platform is set up', async () => {
    render(<ScmConnections />);
    fireEvent.click((await screen.findByText('Set up')));

    const secret = (await screen.findByLabelText(/Client secret/)) as HTMLInputElement;
    expect(secret.required).toBe(true);
    expect(secret.placeholder).toBe('');
  });

  it('surfaces a rejected save instead of appearing to have stored it', async () => {
    vi.spyOn(api, 'saveScmOAuthApp').mockRejectedValue(new Error('TEST-REJECTED'));

    render(<ScmConnections />);
    fireEvent.click((await screen.findAllByText('Edit'))[0]);
    fireEvent.submit(document.querySelector('form.oauth-app-form') as HTMLFormElement);

    await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/TEST-REJECTED/));
  });
});
