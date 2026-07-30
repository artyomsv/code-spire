/** The credential-check fields shared by all three provider kinds. */
export interface LastChecked {
  lastCheckAt: string | null;
  lastCheckOk: boolean | null;
  lastCheckError: string | null;
}

/**
 * One line for the credential's standing. Three states, deliberately distinct: never checked is
 * information rather than a problem, which is why it is shown here instead of raising an
 * attention row for every provider whose Check button was never pressed.
 */
export function lastCheckedLabel(item: LastChecked): string {
  if (item.lastCheckAt === null || item.lastCheckOk === null) return 'Never checked';
  const when = new Date(item.lastCheckAt).toLocaleString();
  if (item.lastCheckOk) return `Checked ${when}`;
  return item.lastCheckError
    ? `Rejected ${when} — ${item.lastCheckError}`
    : `Rejected ${when}`;
}
