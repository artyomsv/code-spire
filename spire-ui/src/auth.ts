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
 * Start the login flow.
 *
 * A single-page app cannot run an authorization-code flow from `fetch`: the redirect is cross-origin
 * and dies as an opaque failure. The whole window has to navigate instead, which is why this is a
 * location assignment rather than a request.
 */
export function goToLogin(): void {
  window.location.assign('/api/auth/login');
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
    goToLogin();
  }
  return res;
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

/** Whether the interface should offer actions that change configuration or spend money. */
export function canAdminister(me: Me | null): boolean {
  if (!me) return true; // unknown: let the API be the authority and answer 403
  if (!me.authEnabled) return true;
  return me.roles.includes('spire-admin');
}

/** Whether a login prompt should be shown instead of the dashboard. */
export function needsLogin(me: Me | null): boolean {
  return me !== null && me.authEnabled && !me.authenticated;
}
