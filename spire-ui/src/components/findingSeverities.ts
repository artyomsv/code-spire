import type { Finding, SeverityCounts } from '../api';

/** A finding's severity as the findings card names it; the list's badges reuse its words and colours. */
export type FindingSeverity = Finding['sev'];

/** Most severe first, the order the badges are drawn and the words are said in. */
export const SEVERITY_ORDER: readonly FindingSeverity[] = ['critical', 'warning', 'suggestion', 'nit'];

const PLURAL: Record<FindingSeverity, string> = {
  critical: 'critical', warning: 'warnings', suggestion: 'suggestions', nit: 'nits',
};

/** The most severities that get a badge of their own; the findings of the rest share one "+N" badge. */
export const MAX_SEVERITY_BADGES = 3;

export interface SeverityCount {
  sev: FindingSeverity;
  count: number;
}

export interface SeverityBadge {
  key: FindingSeverity | 'more';
  text: string;
}

/**
 * The severities with open findings, most severe first, or null when the row cannot be drawn as badges: no open
 * findings, no per-severity counts (an older server, or a live frame sent before it had them), or counts that do not
 * add up to `findings`. A null keeps the single number, which is never wrong about the total.
 */
export function presentSeverities(findings: number, counts?: SeverityCounts | null): SeverityCount[] | null {
  if (!counts || findings === 0) return null;
  const present = SEVERITY_ORDER.map((sev) => ({ sev, count: counts[sev] ?? 0 })).filter((c) => c.count > 0);
  const total = present.reduce((sum, c) => sum + c.count, 0);
  return total === findings ? present : null;
}

/** "1 critical, 2 warnings, 4 suggestions": every severity present, including those folded into "+N". */
export function severityWords(present: readonly SeverityCount[]): string {
  return present.map(({ sev, count }) => `${count} ${count === 1 ? sev : PLURAL[sev]}`).join(', ');
}

/** One badge per severity up to the cap, then one "+N" carrying the findings of the severities left out. */
export function severityBadges(present: readonly SeverityCount[]): SeverityBadge[] {
  const shown: SeverityBadge[] = present.slice(0, MAX_SEVERITY_BADGES).map(({ sev, count }) => ({
    key: sev, text: String(count),
  }));
  const rest = present.slice(MAX_SEVERITY_BADGES).reduce((sum, c) => sum + c.count, 0);
  return rest > 0 ? [...shown, { key: 'more', text: `+${rest}` }] : shown;
}
