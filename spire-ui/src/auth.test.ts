import { describe, it, expect, vi, afterEach } from 'vitest';
import { apiFetch, canAdminister, fetchMe, isAuthFailure, needsLogin, type Me } from './auth';

const me = (over: Partial<Me> = {}): Me => ({
  authEnabled: true,
  authenticated: true,
  user: 'dev-operator',
  roles: ['spire-admin', 'spire-viewer'],
  ...over,
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('auth failure detection', () => {
  /**
   * 499 is what a script-initiated request receives instead of a redirect it cannot follow, and 401
   * is what a bearer-shaped call receives. Both mean "log in"; neither is an application error.
   */
  it('treats 499 and 401 as needing a login, and nothing else', () => {
    expect(isAuthFailure(499)).toBe(true);
    expect(isAuthFailure(401)).toBe(true);
    expect(isAuthFailure(403)).toBe(false); // signed in, wrong role — a login would not help
    expect(isAuthFailure(500)).toBe(false);
    expect(isAuthFailure(200)).toBe(false);
  });
});

describe('apiFetch', () => {
  /** Without the marker the server answers 302, which the browser turns into an opaque failure. */
  it('marks every request as script-initiated', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    await apiFetch('/api/reviews');

    expect(fetchMock.mock.calls[0][1].headers).toMatchObject({ 'X-Requested-With': 'JavaScript' });
  });

  it('preserves caller headers alongside the marker', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ status: 200 });
    vi.stubGlobal('fetch', fetchMock);

    await apiFetch('/api/reviews', { headers: { 'Content-Type': 'application/json' } });

    expect(fetchMock.mock.calls[0][1].headers).toMatchObject({
      'Content-Type': 'application/json',
      'X-Requested-With': 'JavaScript',
    });
  });

  it('sends the operator to a login when the session has gone', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 499 }));
    const assign = vi.fn();
    vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost' });

    await apiFetch('/api/reviews');

    expect(assign).toHaveBeenCalledWith('/api/auth/login');
  });

  /** A wrong-role refusal must NOT bounce to a login — logging in again changes nothing. */
  it('does not redirect on a forbidden response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 403 }));
    const assign = vi.fn();
    vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost' });

    await apiFetch('/api/dlq');

    expect(assign).not.toHaveBeenCalled();
  });
});

describe('fetchMe', () => {
  /**
   * A dashboard that cannot reach /api/me should carry on rather than blank itself — the individual
   * calls will fail honestly on their own if the service is really down.
   */
  it('returns null instead of throwing when the endpoint is unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network')));
    expect(await fetchMe()).toBeNull();
  });

  it('returns null on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }));
    expect(await fetchMe()).toBeNull();
  });
});

describe('what the interface should offer', () => {
  it('offers admin actions to an admin and withholds them from a viewer', () => {
    expect(canAdminister(me())).toBe(true);
    expect(canAdminister(me({ roles: ['spire-viewer'] }))).toBe(false);
  });

  /** With authentication switched off there are no roles to check, and dev must not be crippled. */
  it('offers everything when authentication is switched off', () => {
    expect(canAdminister(me({ authEnabled: false, authenticated: false, roles: [] }))).toBe(true);
  });

  /**
   * Unknown state defers to the API rather than guessing. Hiding actions on an unreachable /api/me
   * would make a transient blip look like a permissions change; the server still answers 403 if the
   * operator really cannot do it.
   */
  it('defers to the API when the session state is unknown', () => {
    expect(canAdminister(null)).toBe(true);
  });

  it('asks for a login only when authentication is on and nobody is signed in', () => {
    expect(needsLogin(me({ authenticated: false }))).toBe(true);
    expect(needsLogin(me())).toBe(false);
    expect(needsLogin(me({ authEnabled: false, authenticated: false }))).toBe(false);
    expect(needsLogin(null)).toBe(false); // unknown is not a reason to demand a login
  });
});
