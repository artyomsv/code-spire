import type { ServingAccount, ServingState } from '../api';

export interface ServingChip {
  label: string;
  pill: string; // an existing .pill tone: completed | refused | cancelled
  title: string;
}

/**
 * How each serving state reads, in the review pills' own tones. Amber (`refused`) for the two
 * half-registered states because each is a registration that exists and will not work as an
 * operator expects; grey for disabled and missing, whose cures differ but whose colour should not.
 */
const TONE: Record<ServingState, { pill: string; title: string }> = {
  ok: { pill: 'completed', title: 'Enabled and usable.' },
  'no-identity': {
    pill: 'refused',
    title:
      'Enabled, but the bot’s own identity is not resolved. It reviews, but cannot recognise its own ' +
      'comments, so conversation follow-ups are skipped. Re-save the account with a token the forge can identify.',
  },
  'no-login': {
    pill: 'refused',
    title:
      'Enabled, but no login is resolved, and a push is authenticated as one. POST /api/runs answers 409 ' +
      'until the account is re-saved with a token the forge can identify.',
  },
  disabled: { pill: 'cancelled', title: 'Registered, but disabled.' },
  missing: { pill: 'cancelled', title: 'No account is registered for this role.' },
};

/**
 * The chip for one role on one repository row. `undefined` with no failure is a lookup in flight;
 * a failure renders as unknown with the error as the title. An unlisted state is unknown too — grey,
 * named, and never green: the `refused` status once rendered as five green segments because the
 * reader defaulted into the success branch.
 */
export function servingChip(account: ServingAccount | undefined, failure: string | null): ServingChip {
  if (failure !== null) {
    return { label: 'unknown', pill: 'cancelled', title: failure };
  }
  if (account === undefined) {
    return { label: 'loading…', pill: 'cancelled', title: 'Loading' };
  }
  const tone = TONE[account.state];
  if (tone === undefined) {
    return {
      label: `unknown (${account.state})`,
      pill: 'cancelled',
      title: 'The server reported a state this dashboard does not know. Shown grey, never green.',
    };
  }
  if (account.state === 'missing') {
    return { label: 'none', pill: tone.pill, title: tone.title };
  }
  return { label: account.name ?? account.id ?? '?', pill: tone.pill, title: tone.title };
}
