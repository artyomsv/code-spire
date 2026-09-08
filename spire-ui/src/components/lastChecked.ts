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
 *
 * <p>The date only. This label sits in a table cell beside the Check control, and the time of day
 * pushed every row on Accounts onto a second line for a precision no operator reads at a glance.
 * The full timestamp is still one hover away — see {@link lastCheckedTitle}.
 */
export function lastCheckedLabel(item: LastChecked): string {
  if (item.lastCheckAt === null || item.lastCheckOk === null) return 'Never checked';
  const when = new Date(item.lastCheckAt).toLocaleDateString();
  if (item.lastCheckOk) return `Checked ${when}`;
  return item.lastCheckError
    ? `Rejected ${when} — ${item.lastCheckError}`
    : `Rejected ${when}`;
}

/**
 * The same standing with the time of day, for the badge's tooltip. Empty when the credential was
 * never checked: there is no timestamp to enlarge on, and a tooltip repeating the visible label
 * teaches the operator that hovering these badges is pointless.
 */
export function lastCheckedTitle(item: LastChecked): string {
  if (item.lastCheckAt === null || item.lastCheckOk === null) return '';
  const when = new Date(item.lastCheckAt).toLocaleString();
  if (item.lastCheckOk) return `Checked ${when}`;
  return item.lastCheckError
    ? `Rejected ${when} — ${item.lastCheckError}`
    : `Rejected ${when}`;
}
