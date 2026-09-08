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
  return standing(item, new Date(item.lastCheckAt).toLocaleDateString());
}

/**
 * The same standing with the time of day, for the badge's tooltip. Empty when the credential was
 * never checked: there is no timestamp to enlarge on, and a tooltip repeating the visible label
 * teaches the operator that hovering these badges is pointless.
 *
 * <p>On a rejection this is the whole message, not a longer date. The badge is bounded and ellipses,
 * and a provider's refusal can be a paragraph, so the hover is the only place the operator can read
 * why the token was refused.
 */
export function lastCheckedTitle(item: LastChecked): string {
  if (item.lastCheckAt === null || item.lastCheckOk === null) return '';
  return standing(item, new Date(item.lastCheckAt).toLocaleString());
}

/** The standing itself, given an already-formatted timestamp. One wording, two precisions. */
function standing(item: LastChecked, when: string): string {
  if (item.lastCheckOk) return `Checked ${when}`;
  return item.lastCheckError
    ? `Rejected ${when} — ${item.lastCheckError}`
    : `Rejected ${when}`;
}
