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

    await waitFor(() => expect(screen.getByText('github')).toBeTruthy());
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

    const field = (await screen.findByLabelText(/Redirect address/)) as HTMLInputElement;
    expect(field.value).toBe('https://spire.example.invalid/api/operator-connect/github/callback');
    expect(field.readOnly).toBe(true);
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
