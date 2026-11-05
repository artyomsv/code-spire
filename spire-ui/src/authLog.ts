/**
 * A short record of why the window left or arrived, kept per tab.
 *
 * <p>The dashboard sometimes reloads on its own around a sign-in, and the cause could not be
 * reproduced on demand. A login is a whole-window navigation, so the console that saw it start is
 * cleared by it: nothing printed there survives to be read. This log is kept in sessionStorage
 * instead, and every page load prints it again, so the operator can open the console after the
 * reload and still see what started it.
 *
 * <p>Only events, fixed reasons and times are stored. A referrer is reduced to its origin: the
 * identity provider's callback carries the authorization code and state in the query, and a path can
 * name a person. Which service the page came from is all the diagnosis needs.
 */

/** Versioned, so entries an earlier build stored with referrer paths are never read again. */
export const AUTH_LOG_KEY = 'spire.auth.log.v2';

/** The key an earlier build of this log used. Deleted on every page load, never read. */
export const LEGACY_AUTH_LOG_KEY = 'spire.auth.log';

/** Enough to cover a burst of reloads, small enough to read at a glance. */
export const AUTH_LOG_MAX = 30;

const CONSOLE_TAG = '[spire auth]';

export interface AuthLogEntry {
  at: string;
  event: string;
  detail: string;
}

/** The stored entries, oldest first. Empty when there are none or they cannot be read. */
export function readAuthLog(): AuthLogEntry[] {
  try {
    const parsed: unknown = JSON.parse(sessionStorage.getItem(AUTH_LOG_KEY) ?? '[]');
    return Array.isArray(parsed) ? (parsed as AuthLogEntry[]) : [];
  } catch {
    return [];
  }
}

/** Add one entry and print it. Never throws: losing a diagnostic must not cost a login. */
export function recordAuthEvent(event: string, detail: string): void {
  const entry: AuthLogEntry = { at: new Date().toISOString(), event, detail };
  console.info(CONSOLE_TAG, entry.at, event, detail);
  try {
    const log = [...readAuthLog(), entry].slice(-AUTH_LOG_MAX);
    sessionStorage.setItem(AUTH_LOG_KEY, JSON.stringify(log));
  } catch {
    // Site data blocked. The entry was still printed.
  }
}

/** The referrer's origin only, or "(none)". */
function originOf(referrer: string): string {
  if (!referrer) return '(none)';
  try {
    return new URL(referrer).origin;
  } catch {
    return '(unreadable)';
  }
}

function deleteLegacyLog(): void {
  try {
    sessionStorage.removeItem(LEGACY_AUTH_LOG_KEY);
  } catch {
    // Site data blocked: there is nothing stored to delete either.
  }
}

/**
 * Record this page load, then print the whole log so far.
 *
 * @param navigationType the Navigation Timing type: navigate, reload, back_forward or prerender
 * @param referrer where the page came from, as `document.referrer` reports it
 */
export function recordPageLoad(navigationType: string, referrer: string): void {
  deleteLegacyLog();
  recordAuthEvent('page load', `${navigationType} from ${originOf(referrer)}`);
  console.info('[spire auth] recent events', readAuthLog());
}
