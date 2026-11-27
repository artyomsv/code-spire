/** Open findings per severity, keyed by the findings card's slugs (`Finding['sev']`): a list row's findings badges. */
export interface SeverityCounts {
  critical: number;
  warning: number;
  suggestion: number;
  nit: number;
}
