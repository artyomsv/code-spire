import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import {
  apiFetch,
  canAdminister,
  ensureServiceSessions,
  fetchMe,
  hasRole,
  isAuthFailure,
  needsLogin,
  prefixFor,
  type Me,
} from './auth';

const me = (over: Partial<Me> = {}): Me => ({
  authEnabled: true,
  authenticated: true,
  user: 'dev-operator',
  roles: ['spire-admin', 'spire-viewer'],
  ...over,
});

/**
 * A fresh copy of the module.
 *
 * `leavingForAuth` is module state and deliberately one-way — in a real page the window is unloading,
 * so there is nothing to reset. In tests that makes it contagious: the first test to trigger a
 * navigation would silence every later one, and they would pass or fail on their position in the file.
 * Any test that navigates takes its own instance.
 */
const freshAuth = async () => {
  vi.resetModules();
  return import('./auth');
};

const stubLocation = () => {
  const assign = vi.fn();
  vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost' });
  return assign;
};

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
    const auth = await freshAuth();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 499 }));
    const assign = stubLocation();

    await auth.apiFetch('/api/reviews');

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

  /**
   * The bug this exists for: each service is a separate session behind its own prefix, so a `/gw`
   * refusal has to be answered by the GATEWAY's login. Sending it to the dashboard's login re-mints a
   * cookie that was never missing and returns to a page that fails again — a loop that presents as
   * "failed to fetch" on one screen while the rest of the dashboard works.
   */
  it.each([
    ['/gw/webhook-repos', '/gw/auth/login'],
    ['/wk/review-context/a/b/1', '/wk/auth/login'],
  ])('sends a refusal on %s to %s', async (path, login) => {
    // One case per test, each on its own module instance: the first navigation wins by design, so
    // asserting both in one test would only ever exercise the first.
    const auth = await freshAuth();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 499 }));
    const assign = stubLocation();

    await auth.apiFetch(path);

    expect(assign).toHaveBeenCalledWith(login);
  });
});

/**
 * Two glitches came from the same gap: the app had no notion of "we are on our way to the identity
 * provider", so every failure caused by leaving was reported as if it had happened while staying.
 * Most visibly on logout — the operator pressed Sign out and got a red failure message.
 *
 */
describe('leaving for the identity provider', () => {
  it('reports nothing until a login or logout has actually started', async () => {
    const auth = await freshAuth();
    expect(auth.isLeavingForAuth()).toBe(false);
  });

  it('marks the departure before navigating, so failures on the way out are recognised', async () => {
    const auth = await freshAuth();
    const assign = stubLocation();

    auth.goToLogout();

    expect(assign).toHaveBeenCalledWith('/api/auth/logout');
    expect(auth.isLeavingForAuth()).toBe(true);
  });

  /**
   * The logout race. A logout closes every socket and fails every in-flight call; each of those used
   * to be read as "the session lapsed" and answered with a login — turning a deliberate sign-out into
   * a sign-in, and on an SSO session straight back into the dashboard.
   */
  it('does not turn a logout in progress back into a login', async () => {
    const auth = await freshAuth();
    const assign = stubLocation();

    auth.goToLogout();
    assign.mockClear();

    auth.goToLogin('/gw');
    auth.goToLogin();

    expect(assign).not.toHaveBeenCalled();
  });

  /** Several things notice a lapsed session at once; they must not each navigate. */
  it('navigates once however many callers notice', async () => {
    const auth = await freshAuth();
    const assign = stubLocation();

    auth.goToLogin();
    auth.goToLogin('/gw');
    auth.goToLogin('/wk');

    expect(assign).toHaveBeenCalledTimes(1);
    expect(assign).toHaveBeenCalledWith('/api/auth/login');
  });
});

describe('which service a call belongs to', () => {
  it('reads the prefix from the path and defaults to the dashboard', () => {
    expect(prefixFor('/gw/webhook-repos')).toBe('/gw');
    expect(prefixFor('/wk/review-context/a/b/1')).toBe('/wk');
    expect(prefixFor('/api/reviews')).toBe('/api');
    // Not a prefix match: `/gwx/...` is a different path, and a bare `/gw` is not a call to anything.
    expect(prefixFor('/gwx/thing')).toBe('/api');
    expect(prefixFor('/q/health')).toBe('/api');
  });
});

describe('establishing the other services sessions', () => {
  const okResponse = { ok: true, status: 200 };

  beforeEach(() => {
    sessionStorage.clear();
  });

  it('does nothing when every service already has a session', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(okResponse));
    const assign = vi.fn();
    vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost' });

    expect(await ensureServiceSessions()).toBe(false);
    expect(assign).not.toHaveBeenCalled();
  });

  it('logs in to the service that has none', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => Promise.resolve(url.startsWith('/gw') ? { ok: false, status: 499 } : okResponse)),
    );
    const assign = vi.fn();
    vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost' });

    expect(await ensureServiceSessions()).toBe(true);
    expect(assign).toHaveBeenCalledWith('/gw/auth/login');
  });

  /**
   * A prefix that still refuses after its own login cannot be fixed by logging in again — without
   * this the dashboard would reload forever, which is worse than the broken page it replaced.
   */
  it('gives up on a prefix that refuses even after being logged in to', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => Promise.resolve(url.startsWith('/gw') ? { ok: false, status: 499 } : okResponse)),
    );
    const assign = vi.fn();
    vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost' });

    expect(await ensureServiceSessions()).toBe(true);
    assign.mockClear();

    expect(await ensureServiceSessions()).toBe(false);
    expect(assign).not.toHaveBeenCalled();
  });

  /** An unreachable service is an outage. Navigating to its login would blame the operator for it. */
  it('does not try to log in to a service that cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network')));
    const assign = vi.fn();
    vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost' });

    expect(await ensureServiceSessions()).toBe(false);
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
   * Absence of an answer is not a grant. This deliberately reversed an earlier decision to return
   * true here on the grounds that the API is the real authority and would answer 403 anyway — sound
   * about security, wrong about interface: it meant every operator, viewer included, was shown the
   * full admin surface for as long as `/api/me` took to answer, and then had it withdrawn. An option
   * disappearing reads as a malfunction; one appearing reads as loading.
   */
  it('grants nothing while the session state is unknown', () => {
    expect(canAdminister(null)).toBe(false);
  });

  /** The role is a required literal, so no call site can ask "any role at all" and be admitted. */
  it('checks the role it is given, not merely that someone is signed in', () => {
    expect(hasRole(me({ roles: ['spire-viewer'] }), 'spire-viewer')).toBe(true);
    expect(hasRole(me({ roles: ['spire-viewer'] }), 'spire-admin')).toBe(false);
    expect(hasRole(null, 'spire-viewer')).toBe(false);
  });

  it('asks for a login only when authentication is on and nobody is signed in', () => {
    expect(needsLogin(me({ authenticated: false }))).toBe(true);
    expect(needsLogin(me())).toBe(false);
    expect(needsLogin(me({ authEnabled: false, authenticated: false }))).toBe(false);
    expect(needsLogin(null)).toBe(false); // unknown is not a reason to demand a login
  });
});
