import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ConnectOptions, connectOutcome } from './ConnectOptions';
import * as api from '../api';
import * as auth from '../auth';

/**
 * An operator proving their own SCM account (P4 / FR-11).
 *
 * <p>Two properties matter here and neither is cosmetic. The button must be a real link, because
 * the server answers with a redirect to the platform and a cross-origin redirect reaches `fetch` as
 * an opaque failure — a button that called the API would silently do nothing. And an outcome the
 * code does not recognise must never read as success: a reassuring default for an unhandled case is
 * how a refused review once rendered as five green segments under "done".
 */

describe('ConnectOptions', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('offers a real navigation, not a fetch, for each configured platform', async () => {
    vi.spyOn(api, 'fetchConnectablePlatforms').mockResolvedValue([
      { providerType: 'github', configured: true, linked: false, authorId: '' },
    ]);

    render(<ConnectOptions />);

    const link = await screen.findByText(/Connect my github account/);
    const anchor = link.closest('a') as HTMLAnchorElement;
    expect(anchor).toBeTruthy();
    expect(anchor.getAttribute('href')).toBe('/api/operator-connect/github/start');
  });

  /** Already proved: offering it again would suggest the link had not taken. */
  it('does not offer a platform the operator has already connected', async () => {
    vi.spyOn(api, 'fetchConnectablePlatforms').mockResolvedValue([
      { providerType: 'github', configured: true, linked: true, authorId: '3218389' },
    ]);

    render(<ConnectOptions />);

    await waitFor(() => expect(screen.queryByText(/Loading/)).toBeNull());
    expect(screen.queryByText(/Connect my github account/)).toBeNull();
  });

  /**
   * A platform with no application set up is not hidden. Hiding it leaves an operator with no idea
   * why their account is not offered; the thing they need to know is that an admin has one step.
   */
  it('says what to do when no platform is set up rather than showing nothing', async () => {
    vi.spyOn(api, 'fetchConnectablePlatforms').mockResolvedValue([
      { providerType: 'gitlab', configured: false, linked: false, authorId: '' },
    ]);

    render(<ConnectOptions />);

    expect(await screen.findByText(/No platform is set up for sign-in yet/)).toBeTruthy();
    expect(screen.getByText(/Settings → Operators/)).toBeTruthy();
  });

  /**
   * A sign-in proves WHOSE account it is, so with authentication off there is nothing to attach the
   * proof to and the server refuses. Said before the click, not after: otherwise an operator visits
   * the platform, authorizes a real application, and comes back to a decline.
   */
  it('says authentication is off rather than offering a sign-in that cannot work', async () => {
    vi.spyOn(api, 'fetchConnectablePlatforms').mockResolvedValue([
      { providerType: 'github', configured: true, linked: false, authorId: '' },
    ]);
    vi.spyOn(auth, 'fetchMe').mockResolvedValue({
      authEnabled: false,
      authenticated: false,
      user: '',
      roles: [],
    });

    render(<ConnectOptions />);

    expect(await screen.findByText(/Authentication is off in this deployment/)).toBeTruthy();
    expect(screen.queryByText(/Connect my github account/)).toBeNull();
  });

  it('reports a failed load instead of looking like nothing is available', async () => {
    vi.spyOn(api, 'fetchConnectablePlatforms').mockRejectedValue(new Error('TEST-UNREACHABLE'));

    render(<ConnectOptions />);

    expect((await screen.findByRole('alert')).textContent).toMatch(/TEST-UNREACHABLE/);
  });
});

describe('connectOutcome', () => {
  it('says nothing when no attempt was made', () => {
    expect(connectOutcome(null)).toBeNull();
  });

  it('reports success only for the success code', () => {
    expect(connectOutcome('connected')?.ok).toBe(true);
    expect(connectOutcome('declined')?.ok).toBe(false);
    expect(connectOutcome('expired')?.ok).toBe(false);
    expect(connectOutcome('mismatch')?.ok).toBe(false);
    expect(connectOutcome('refused')?.ok).toBe(false);
    expect(connectOutcome('noaccount')?.ok).toBe(false);
    expect(connectOutcome('unconfigured')?.ok).toBe(false);
    expect(connectOutcome('noidentity')?.ok).toBe(false);
  });

  /**
   * The case this vocabulary exists to get right. An outcome nobody handled is a failure, and
   * defaulting it to success would tell an operator they are linked when they are not — after which
   * the empty activity screen looks like a bug in the analytics rather than an unfinished sign-in.
   */
  it('treats an unrecognised outcome as a failure, never as success', () => {
    expect(connectOutcome('something-nobody-wrote')?.ok).toBe(false);
  });

  /** Each message has to name the next step; "it did not work" sends an operator to an admin blind. */
  it('tells the operator what to do next', () => {
    expect(connectOutcome('expired')?.text).toMatch(/again/);
    expect(connectOutcome('refused')?.text).toMatch(/admin/);
  });
});
