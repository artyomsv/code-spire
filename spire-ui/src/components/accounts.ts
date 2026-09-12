/**
 * Pure helpers for the Accounts screen. In their own module so the list can be tested without
 * rendering, and so the role reader has exactly one implementation.
 */

/** A role arrives as JSON; anything outside the known values is reported, not defaulted. */
export function roleLabel(role: string | null | undefined): string {
  switch (role) {
    case 'REVIEWER':
      return 'Reviewer';
    case 'FACTORY':
      return 'Factory';
    case 'CONTEXT':
      return 'Context';
    default:
      return `Unknown (${role ?? ''})`;
  }
}

/** What an account is, as a word. The table draws it as an icon and keeps the word in the label. */
export type AccountKind = 'Forge' | 'Tracker' | 'Knowledge';

/** The code index reads a repository; every other context source reads a tracker or a wiki. */
export function accountKind(contextType: string): AccountKind {
  return contextType === 'code' ? 'Knowledge' : 'Tracker';
}

/** The host is what identifies a tracker account's scope; a value that is not a URL is shown as is. */
export function hostOf(baseUrl: string): string {
  try {
    return new URL(baseUrl).host;
  } catch {
    return baseUrl;
  }
}

export function scopeLabel(scopes: string | null | undefined): string {
  return scopes == null ? 'This token kind does not report its scopes'
    : scopes.trim() ? 'Token reports: ' + scopes : 'Token reports no scopes';
}
