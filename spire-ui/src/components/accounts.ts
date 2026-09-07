/**
 * Pure helpers for the Accounts screen. In their own module so the list can be tested without
 * rendering, and so the role reader has exactly one implementation.
 */

/** A role arrives as JSON; anything outside the two known values is reported, not defaulted. */
export function roleLabel(role: string | null | undefined): string {
  switch (role) {
    case 'REVIEWER':
      return 'Reviewer';
    case 'FACTORY':
      return 'Factory';
    default:
      return `Unknown (${role ?? ''})`;
  }
}

/** The code index reads a repository; every other context source reads a tracker or a wiki. */
export function accountKind(contextType: string): 'Knowledge' | 'Tracker' {
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
