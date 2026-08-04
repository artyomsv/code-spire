/**
 * The dashboard's side of operator authentication (D10 slice 4).
 *
 * Three states have to be told apart, and they are not the same thing:
 *   - authentication is switched off entirely (dev) — render normally, never show a login
 *   - it is on and nobody is signed in — show a login, and do not hammer the API
 *   - it is on and someone is — render, and hide what their role does not permit
 *
 * `/api/me` is the only endpoint that answers all three, which is why it is deliberately readable
 * without a session.
 */

export interface Me {
  authEnabled: boolean;
  authenticated: boolean;
  user: string;
  roles: string[];
}

/**
 * Marks a request as script-initiated.
 *
 * Without it an unauthenticated `fetch` receives a 302 to the identity provider, which the browser
 * follows cross-origin and reports as an opaque CORS failure — the app never sees a status it can
 * act on. With it, the server answers a distinct status instead, so the app can send the operator to
 * a login rather than showing a broken screen. Measured in the phase-0 spike: both `JavaScript` and
 * `XMLHttpRequest` produce the intended response.
 */
export const SCRIPT_REQUEST_HEADER = { 'X-Requested-With': 'JavaScript' } as const;

/** The status a script-initiated request gets instead of a redirect it cannot follow. */
export const AUTH_REQUIRED_STATUS = 499;

export function isAuthFailure(status: number): boolean {
  return status === AUTH_REQUIRED_STATUS || status === 401;
}

/**
 * The URL prefixes that are separate sessions.
 *
 * Each service is its own OIDC client with its own cookie scoped to its own prefix (ADR-022), so
 * "signed in" is **per prefix**, not per browser. Signing in to the dashboard mints `/api` and
 * nothing else; the gateway's registry and the worker's review-context each need their own. Getting
 * this wrong does not look like an authentication problem from the outside — it looks like one page
 * failing to fetch while the rest of the dashboard works.
 */
export const SESSION_PREFIXES = ['/api', '/gw', '/wk'] as const;
export type SessionPrefix = (typeof SESSION_PREFIXES)[number];

/** Which service's session a request needs, from its path. Anything unrecognised is the dashboard's. */
export function prefixFor(url: string): SessionPrefix {
  return SESSION_PREFIXES.find((p) => url.startsWith(`${p}/`)) ?? '/api';
}

/**
 * Whether the window is on its way to the identity provider, for a login or a logout.
 *
 * <p>Once this is true the page is unloading and everything it still has in flight will fail. Those
 * failures are not news: they are the session being taken away, which is the thing we asked for. The
 * hooks consult this before rendering an error or reporting a feed as down, because without it a
 * logout produced a red failure message on the way out — the dashboard reporting, accurately and
 * uselessly, that it could no longer reach an API it had just signed out of.
 */
let leavingForAuth = false;

export function isLeavingForAuth(): boolean {
  return leavingForAuth;
}

/**
 * Start the login flow for one service.
 *
 * A single-page app cannot run an authorization-code flow from `fetch`: the redirect is cross-origin
 * and dies as an opaque failure. The whole window has to navigate instead, which is why this is a
 * location assignment rather than a request. Where the provider session already exists — the usual
 * case for a sibling prefix — the flow completes without prompting and comes straight back.
 *
 * <p>First caller wins. A lapsed session is noticed by several things at once — a REST call, the
 * reviews socket, the attention socket — and each used to assign `location` independently. Worse, a
 * deliberate **logout** was noticed the same way and turned straight back into a login, so signing
 * out raced signing in.
 */
export function goToLogin(prefix: SessionPrefix = '/api'): void {
  if (leavingForAuth) return;
  leavingForAuth = true;
  window.location.assign(`${prefix}/auth/login`);
}

/**
 * End the session — at the identity provider as well as here.
 *
 * Also a whole-window navigation, for the same reason as login: the provider's logout is a redirect
 * chain, not something `fetch` can complete. Dropping only the local cookie would leave the
 * provider's own session intact, so the next login would silently sign the operator straight back
 * in — which reads as logout being broken.
 */
export function goToLogout(): void {
  // Set BEFORE navigating: the session dies the moment the provider is reached, and everything the
  // page still has open fails on the way out. Marked first so those failures are recognised as this
  // logout rather than as an outage to report or a login to start.
  leavingForAuth = true;
  window.location.assign('/api/auth/logout');
}

/**
 * `fetch` for the dashboard's own API.
 *
 * Adds the script marker to every call, and converts an authentication failure into a login rather
 * than letting it surface as an unexplained error somewhere in the interface.
 */
export async function apiFetch(input: string, init?: RequestInit): Promise<Response> {
  const res = await fetch(input, {
    ...init,
    headers: { ...(init?.headers ?? {}), ...SCRIPT_REQUEST_HEADER },
  });
  if (isAuthFailure(res.status)) {
    // The login of whichever service refused — sending a /gw refusal to the orchestrator's login
    // would mint the cookie that was already present and return to a page that fails again.
    goToLogin(prefixFor(input));
  }
  return res;
}

/**
 * Make sure every service's session exists, once per tab.
 *
 * <p>Called after the dashboard learns it is signed in. Each sibling prefix is probed with a
 * script-marked request; a refusal means that service has no cookie yet, and the window navigates to
 * its login, which completes silently against the existing provider session and returns here.
 *
 * <p>Done eagerly rather than on first use because the attention panel opens a socket to the gateway
 * on every page: a WebSocket handshake cannot follow a redirect either, so a missing `/gw` session
 * surfaced as "the webhook gateway is not responding" — a false outage, retried every 1.5s forever.
 * Waiting for a lazy trigger would mean hitting that on the first page load anyway.
 *
 * <p>`sessionStorage` records the attempt per prefix, so a prefix that refuses even after its login
 * (a missing role, a misconfigured client) reports honestly instead of reloading in a loop. A
 * subsequent success clears the mark, so a session that merely lapsed can be renewed again later.
 *
 * @returns true if the window is navigating to a login — callers must then do nothing further, since
 *          anything after this races the unload.
 */
export async function ensureServiceSessions(): Promise<boolean> {
  for (const prefix of SESSION_PREFIXES) {
    if (prefix === '/api') continue; // the dashboard's own session is how we got here
    const attempted = `spire.session.attempted${prefix}`;
    let res: Response;
    try {
      res = await fetch(`${prefix}/auth/login`, { headers: SCRIPT_REQUEST_HEADER });
    } catch {
      continue; // unreachable is an outage, not a missing session — say nothing and let it surface
    }
    if (res.ok) {
      sessionStorage.removeItem(attempted);
      continue;
    }
    if (!isAuthFailure(res.status)) continue; // a real error is not ours to fix by logging in again
    if (sessionStorage.getItem(attempted)) continue; // already tried; do not loop
    sessionStorage.setItem(attempted, '1');
    goToLogin(prefix);
    return true;
  }
  return false;
}

/**
 * Read the session state. Never throws: a dashboard that cannot reach `/api/me` should render as
 * "authentication unknown, carry on" rather than as a blank page — the individual calls will fail
 * honestly on their own if the service is genuinely down.
 */
export async function fetchMe(): Promise<Me | null> {
  try {
    const res = await fetch('/api/me', { headers: SCRIPT_REQUEST_HEADER });
    if (!res.ok) return null;
    return (await res.json()) as Me;
  } catch {
    return null;
  }
}

/** The roles this application recognises. A literal type so a guard cannot be given a role that
 *  does not exist, or — see {@link hasRole} — no role at all. */
export type SpireRole = 'spire-viewer' | 'spire-admin';

/**
 * Whether a session holds a role. **Absence of an answer is not a grant.**
 *
 * `me` is null both while `/api/me` is in flight and if it could not be reached, and both return
 * false. An earlier version returned *true* for null, reasoning that the API is the real authority
 * and would answer 403 anyway. That is true of security and false of interface: for the ~200ms
 * before the session arrived, every operator — including a viewer — was shown the full admin surface,
 * which then vanished. Options being taken away reads as the app breaking; options appearing reads as
 * the page loading. Only one of those is worth showing anybody, and it is not reachable from a
 * permissive default.
 *
 * The role is a required parameter of a literal type, so there is no way to call this such that it
 * admits everyone.
 */
export function hasRole(me: Me | null, role: SpireRole): boolean {
  if (!me) return false;
  // Dev runs with authentication switched off entirely, so there are no roles to hold. Denying here
  // would leave a dashboard where nothing is operable and no login can fix it.
  if (!me.authEnabled) return true;
  return me.roles.includes(role);
}

/** Whether the interface should offer actions that change configuration or spend money. */
export function canAdminister(me: Me | null): boolean {
  return hasRole(me, 'spire-admin');
}

/** Whether a login prompt should be shown instead of the dashboard. */
export function needsLogin(me: Me | null): boolean {
  return me !== null && me.authEnabled && !me.authenticated;
}
