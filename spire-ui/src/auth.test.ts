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
  RETURN_ROUTE_KEY,
  takeReturnRoute,
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

/**
 * A window that records where it was asked to go, without going.
 *
 * <p>`hash` is part of it because a login now notes the screen it is leaving. jsdom's own `location`
 * cannot have `assign` replaced, so the whole object is stubbed — which means a test that cares about
 * the remembered route has to say what the hash was, since a stub has no history of its own.
 */
const stubLocation = (hash = '') => {
  const assign = vi.fn();
  vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost', hash });
  return assign;
};

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
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

  /**
   * This is the call that decides a cold sign-in, which is why it chains. The dashboard's first data
   * fetch is refused before `/api/me` has answered, and navigation is first-caller-wins — so while this
   * asked for an unchained login it won the race, and each sibling prefix was then discovered missing
   * one page load at a time. Three renders, seen as the app blanking and restarting.
   */
  it('asks for every session when the dashboard itself refuses', async () => {
    const auth = await freshAuth();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 499 }));
    const assign = stubLocation();

    await auth.apiFetch('/api/reviews');

    expect(assign).toHaveBeenCalledWith('/api/auth/login?chain=1');
  });

  /**
   * The other half of the same decision. A sibling refusing while `/api` still answers is ONE lapsed
   * prefix; chaining it would re-establish two sessions nobody asked about, and the unchained endpoint
   * is what the probe reads.
   */
  it('asks only for the prefix that refused when the dashboard session is fine', async () => {
    const auth = await freshAuth();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 499 }));
    const assign = stubLocation();

    await auth.apiFetch('/wk/review-context/acme/repo/7');

    expect(assign).toHaveBeenCalledWith('/wk/auth/login');
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

  /**
   * A cold sign-in needs a session on every prefix, and each needs a real navigation. Asking for them
   * one at a time meant the dashboard booted, fetched, found the next one missing and navigated again —
   * three renders discarded, which reads as the app blanking and restarting. `chain=1` asks the server
   * to hand each hop to the next, so the browser follows one redirect sequence and paints once.
   */
  it('asks for the whole chain when signing in from nothing', async () => {
    const auth = await freshAuth();
    const assign = stubLocation();

    auth.goToFullLogin();

    expect(assign).toHaveBeenCalledWith('/api/auth/login?chain=1');
  });

  /**
   * The other direction matters just as much. Re-establishing one lapsed prefix must NOT chain: the
   * unchained path is also what the session probe reads, and a chained answer would depend on a later
   * prefix — making a healthy service look unauthenticated.
   */
  it('does not chain when re-establishing a single prefix', async () => {
    const auth = await freshAuth();
    const assign = stubLocation();

    auth.goToLogin('/gw');

    expect(assign).toHaveBeenCalledWith('/gw/auth/login');
  });

  it('marks the departure before navigating, so failures on the way out are recognised', async () => {
    const auth = await freshAuth();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 204 }));
    const assign = stubLocation();

    const leaving = auth.goToLogout();
    // Synchronously, before the sibling logouts are even awaited: the sockets begin failing the moment
    // the operator presses Sign out, and those failures must already be attributable to this.
    expect(auth.isLeavingForAuth()).toBe(true);
    await leaving;

    expect(assign).toHaveBeenCalledWith('/api/auth/logout');
  });

  /**
   * Sessions are per prefix (ADR-022), so a logout that clears `/api` alone leaves the others live.
   * Measured before this existed: after signing out, `/gw/webhook-repos/attention` still answered 200
   * and both sibling cookies were still held, until they happened to lapse.
   *
   * <p>Order is part of the contract. The siblings are local logouts and are ended first, by fetch; the
   * navigation goes last because it is the one that ends the session at the provider, and after it
   * nothing further in this document runs.
   */
  it('ends every prefix session, siblings before the provider', async () => {
    const auth = await freshAuth();
    const order: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        order.push(`${init?.method ?? 'GET'} ${url}`);
        return Promise.resolve({ ok: true, status: 204 });
      }),
    );
    const assign = vi.fn(() => order.push('NAVIGATE /api/auth/logout'));
    vi.stubGlobal('location', { assign, protocol: 'http:', host: 'localhost' });

    await auth.goToLogout();

    expect(order).toEqual(['POST /gw/auth/logout', 'POST /wk/auth/logout', 'NAVIGATE /api/auth/logout']);
  });

  /** A sibling being unreachable must not strand the operator half signed out. */
  it('still ends the provider session when a sibling logout fails', async () => {
    const auth = await freshAuth();
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) =>
        url.startsWith('/gw') ? Promise.reject(new Error('gateway down')) : Promise.resolve({ ok: true, status: 204 }),
      ),
    );
    const assign = stubLocation();

    await auth.goToLogout();

    expect(assign).toHaveBeenCalledWith('/api/auth/logout');
  });

  /**
   * The logout race. A logout closes every socket and fails every in-flight call; each of those used
   * to be read as "the session lapsed" and answered with a login — turning a deliberate sign-out into
   * a sign-in, and on an SSO session straight back into the dashboard.
   */
  it('does not turn a logout in progress back into a login', async () => {
    const auth = await freshAuth();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 204 }));
    const assign = stubLocation();

    // Deliberately NOT awaited: the point is that a login attempted while the logout is still in
    // flight — which is exactly when the sockets are closing — is refused.
    const leaving = auth.goToLogout();
    assign.mockClear();

    auth.goToLogin('/gw');
    auth.goToLogin();

    expect(assign).not.toHaveBeenCalled();
    await leaving; // let the logout finish, so its own navigation cannot leak into a later test
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

/**
 * The second half of the reported glitch. The dashboard came back from a re-login on Reviews, whatever
 * screen the operator had been on — because the server's login endpoint takes no redirect target from
 * the caller (a client-supplied one is an open redirect) and always returns to `/`. The route is
 * therefore kept on this side of the wire, and never sent anywhere.
 */
describe('coming back to the screen the login left', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('notes the current route before the window leaves for a login', async () => {
    const auth = await freshAuth();
    stubLocation('#/settings/accounts');

    auth.goToFullLogin();

    expect(sessionStorage.getItem(auth.RETURN_ROUTE_KEY)).toBe('#/settings/accounts');
  });

  /** Re-establishing one prefix leaves the page just as thoroughly, so it must remember too. */
  it('notes it for a single-prefix login as well', async () => {
    const auth = await freshAuth();
    stubLocation('#/runs');

    auth.goToLogin('/gw');

    expect(sessionStorage.getItem(auth.RETURN_ROUTE_KEY)).toBe('#/runs');
  });

  /** Both already mean the default screen; storing one would buy a redundant navigation and nothing else. */
  it.each(['', '#/'])('stores nothing for a hash of "%s"', async (hash) => {
    const auth = await freshAuth();
    stubLocation(hash);

    auth.goToFullLogin();

    expect(sessionStorage.getItem(auth.RETURN_ROUTE_KEY)).toBeNull();
  });

  /**
   * Signing out should land on `/`. Returning the operator to the screen they signed out from would
   * either bounce them straight back into a login or, on a shared machine, name the last screen the
   * previous operator was reading.
   */
  it('stores nothing when the operator signs out', async () => {
    const auth = await freshAuth();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 204 }));
    stubLocation('#/settings/accounts');

    await auth.goToLogout();

    expect(sessionStorage.getItem(auth.RETURN_ROUTE_KEY)).toBeNull();
  });

  /** Good for exactly one arrival. Left in place it would bounce every later visit to `/` off the rail. */
  it('gives the route back once and then has none', () => {
    sessionStorage.setItem(RETURN_ROUTE_KEY, '#/settings/accounts');

    expect(takeReturnRoute()).toBe('#/settings/accounts');
    expect(takeReturnRoute()).toBeNull();
  });

  /**
   * Only a hash route of this app is ever returned. Nothing but the login writes this key, so a value
   * of another shape is a leftover or something else on this origin — and either way not a place to
   * send the window.
   */
  it.each(['https://evil.invalid/', '//evil.invalid/', '/settings/accounts', '#not-a-route', ''])(
    'refuses to return %s',
    (stored) => {
      sessionStorage.setItem(RETURN_ROUTE_KEY, stored);

      expect(takeReturnRoute()).toBeNull();
      // Consumed even so: a value this refuses must not sit there being refused on every page load.
      expect(sessionStorage.getItem(RETURN_ROUTE_KEY)).toBeNull();
    },
  );
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

  const marks = () => ['/gw', '/wk'].filter((p) => sessionStorage.getItem(`spire.session.attempted${p}`));

  /**
   * The blink this exists to remove. After the services restart, `/api` can still be valid while BOTH
   * siblings have lapsed — and answering the first refusal on its own meant one full navigation back to
   * the root per lapsed prefix: the page booted, found `/gw`, left; booted again, found `/wk`, left
   * again. The operator saw the dashboard flicker and settle on Reviews.
   *
   * <p>So every sibling is probed before anything navigates, and the lapsed ones are re-established by
   * a single chained login, which the browser follows without painting the documents in between.
   */
  it('re-establishes every lapsed sibling in one chained login', async () => {
    const auth = await freshAuth();
    // 401, not 499: a lapsed cookie on a script-marked call is answered either way, and the loop must
    // treat both as "log in" — see `isAuthFailure`.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    const assign = stubLocation();

    expect(await auth.ensureServiceSessions()).toBe(true);

    expect(assign).toHaveBeenCalledTimes(1);
    expect(assign).toHaveBeenCalledWith('/api/auth/login?chain=1');
    // Both, not just the first one found: a prefix left unmarked is one this navigation is about to
    // establish anyway, and marking it is what stops the next load asking a second time.
    expect(marks()).toEqual(['/gw', '/wk']);
  });

  /**
   * The other half of that guard, on a page that has not navigated yet: every lapsed prefix is
   * already marked, so there is nothing left to try and the window must stay put. Without this the
   * dashboard would reload forever, which is worse than the broken page it replaced.
   */
  it('does not navigate again once every lapsed prefix has been tried', async () => {
    const auth = await freshAuth();
    sessionStorage.setItem('spire.session.attempted/gw', '1');
    sessionStorage.setItem('spire.session.attempted/wk', '1');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }));
    const assign = stubLocation();

    expect(await auth.ensureServiceSessions()).toBe(false);
    expect(assign).not.toHaveBeenCalled();
  });

  /**
   * A prefix that still refuses after its own login cannot be fixed by logging in again, and a prefix
   * that is answering must not be retired alongside it.
   */
  it('gives up on the prefixes that refuse even after being logged in to', async () => {
    // Its own instance, per the convention above: this test navigates, and on the shared module
    // `leavingForAuth` may already be set by an earlier one. That mattered — while the mark was
    // written on intent rather than on the attempt, this test passed with its login suppressed, so it
    // was asserting "gives up after being logged in to" over a login that never ran.
    const auth = await freshAuth();
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => Promise.resolve(url.startsWith('/gw') ? { ok: false, status: 499 } : okResponse)),
    );
    const assign = stubLocation();

    expect(await auth.ensureServiceSessions()).toBe(true);
    expect(assign).toHaveBeenCalledWith('/api/auth/login?chain=1');
    expect(marks()).toEqual(['/gw']); // `/wk` answered, so it is neither lapsed nor retired
    assign.mockClear();

    expect(await auth.ensureServiceSessions()).toBe(false);
    expect(assign).not.toHaveBeenCalled();
  });

  /**
   * Two callers race on a fresh page: App's effect asks for the sibling sessions as soon as `/api/me`
   * answers, and the attention panel's gateway socket — which fails precisely because those sessions
   * do not exist yet — asks again from its close handler. Navigation is first-caller-wins, so the
   * second caller's silently does nothing.
   */
  it('navigates once however many callers ask at the same time', async () => {
    const auth = await freshAuth();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 499 }));
    const assign = stubLocation();

    await Promise.all([auth.ensureServiceSessions(), auth.ensureServiceSessions()]);

    expect(assign).toHaveBeenCalledTimes(1);
    expect(marks()).toEqual(['/gw', '/wk']);
  });

  /**
   * The mark must record a login that RAN, not one that was merely intended — which is why it is
   * written after the navigation call and not before it.
   *
   * <p>Here the window is already leaving for one prefix's own login, started by the attention panel's
   * socket close handler. This call's chained login is therefore suppressed, and nothing it found
   * lapsed has been logged in to. Marking on intent retires those prefixes permanently: the next load
   * sees the marks, skips the prefixes, and those services never get a session for the life of the tab
   * — which the panel then reports as the service being unreachable, forever, 1.5s at a time.
   */
  it('records no attempt when its login was suppressed by a departure already in flight', async () => {
    const auth = await freshAuth();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 499 }));
    const assign = stubLocation();

    expect(auth.goToLogin('/gw')).toBe(true); // something else got there first
    assign.mockClear();

    expect(await auth.ensureServiceSessions()).toBe(true);
    expect(assign).not.toHaveBeenCalled();
    expect(marks()).toEqual([]);
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
