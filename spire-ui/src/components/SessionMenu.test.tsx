import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SessionMenu from './SessionMenu';
import * as auth from '../auth';

/**
 * Who is signed in, and how to stop being them.
 *
 * <p>Both were unreachable before. The three-prefix sign-out existed and was fully tested, but the
 * only control that called it was hidden whenever `authEnabled` was false — so on a dev stack there
 * was nothing on screen at all — and even with authentication on it named the account only in a
 * tooltip. An operator could not tell which account they held, which makes checking that a viewer
 * really is refused impossible without a private browsing window.
 */

const ADMIN: auth.Me = {
  authEnabled: true,
  authenticated: true,
  user: 'test-admin',
  roles: ['spire-admin', 'spire-viewer'],
  subject: 'TEST-SUBJECT-1',
};

const VIEWER: auth.Me = {
  authEnabled: true,
  authenticated: true,
  user: 'test-viewer',
  roles: ['spire-viewer'],
  subject: 'TEST-SUBJECT-2',
};

const AUTH_OFF: auth.Me = {
  authEnabled: false,
  authenticated: false,
  user: '',
  roles: [],
  subject: '',
};

describe('SessionMenu', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('names the signed-in account and its role', () => {
    render(<SessionMenu me={ADMIN} />);
    fireEvent.click(screen.getByTestId('session-toggle'));

    expect(screen.getByText('test-admin')).toBeTruthy();
    // The role as an operator would say it, not as the token spells it.
    expect(screen.getByText(/Administrator/)).toBeTruthy();
  });

  it('distinguishes a viewer from an administrator', () => {
    render(<SessionMenu me={VIEWER} />);
    fireEvent.click(screen.getByTestId('session-toggle'));

    expect(screen.getByText('test-viewer')).toBeTruthy();
    expect(screen.getByText(/Viewer/)).toBeTruthy();
    expect(screen.queryByText(/Administrator/)).toBeNull();
  });

  /** Ends all three prefixes and the provider session — {@link auth.goToLogout} owns that order. */
  it('signs out through the shared routine rather than its own navigation', () => {
    const signOut = vi.spyOn(auth, 'goToLogout').mockResolvedValue(undefined);

    render(<SessionMenu me={ADMIN} />);
    fireEvent.click(screen.getByTestId('session-toggle'));
    fireEvent.click(screen.getByText('Sign out'));

    expect(signOut).toHaveBeenCalled();
  });

  /**
   * The state that made this defect visible. With authentication off the old control rendered
   * nothing, so a dev stack gave no indication of what mode it was in — and an operator looking for
   * their account found an empty corner rather than an answer.
   */
  it('says authentication is off rather than showing nothing', () => {
    render(<SessionMenu me={AUTH_OFF} />);

    expect(screen.getByTestId('session-toggle')).toBeTruthy();
    fireEvent.click(screen.getByTestId('session-toggle'));
    expect(screen.getByText('Authentication off')).toBeTruthy();
    expect(screen.getByText(/no account to sign out of/i)).toBeTruthy();
  });

  /** A sign-out button with no session behind it would be a control that cannot work. */
  it('offers no sign-out when there is no session to end', () => {
    render(<SessionMenu me={AUTH_OFF} />);
    fireEvent.click(screen.getByTestId('session-toggle'));

    expect(screen.queryByText('Sign out')).toBeNull();
  });

  /**
   * The operator id is what an admin needs to link an SCM account, and this is the only screen that
   * knows it — the value appears nowhere else in the product.
   */
  it('shows the operator id so it can be linked', () => {
    render(<SessionMenu me={ADMIN} />);
    fireEvent.click(screen.getByTestId('session-toggle'));

    expect(screen.getByText('TEST-SUBJECT-1')).toBeTruthy();
  });

  /**
   * The session arrives as runtime JSON, and `Me.roles` being typed `string[]` guarantees nothing
   * about what the wire actually sent. This lives in the TOPBAR, so a throw here unmounts the whole
   * shell and the dashboard renders as a blank page — which is how it was found.
   */
  it('survives a session payload that carries no roles', () => {
    const malformed = { authEnabled: true, authenticated: true, user: 'test-user' } as unknown as auth.Me;

    render(<SessionMenu me={malformed} />);
    fireEvent.click(screen.getByTestId('session-toggle'));

    expect(screen.getByText('test-user')).toBeTruthy();
    expect(screen.getByText(/no role/)).toBeTruthy();
  });

  /** Nothing is claimed before the session is known — the same rule the guarded routes follow. */
  it('renders nothing until the session has answered', () => {
    const { container } = render(<SessionMenu me={null} />);
    expect(container.firstChild).toBeNull();
  });
});
