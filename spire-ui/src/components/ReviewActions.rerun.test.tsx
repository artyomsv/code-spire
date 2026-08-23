import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import ReviewActions from './ReviewActions';
import * as auth from '../auth';
import type { Me } from '../auth';
import type { ReviewDetail, ReviewStatus } from '../api';

/**
 * Which statuses offer Re-run.
 *
 * <p>`refused` was omitted, so the button did not render — while `ReviewRerunService.rerun()` gates
 * on nothing but `archived`, so the operation was permitted all along. That is worse than a missing
 * button: the entire recovery path for a cap refusal is *raise the limit, then re-run*, and the
 * refusal note on the same page says exactly that. The advice and the affordance contradicted each
 * other, leaving a push to an already-finished pull request as the only way to act on the advice.
 *
 * <p>Third instance of one class (with the findings card and `findCell`): a status-keyed decision
 * written as a boolean, where a value nobody enumerated falls into whichever branch the expression
 * defaults to — see `techdebt/spire-ui/3-3-…`.
 */

const ADMIN: Me = {
  authEnabled: true,
  authenticated: true,
  user: 'TEST-OPERATOR',
  roles: ['spire-viewer', 'spire-admin'],
};

/** Self-labelling fixture (TEST-*); ReviewActions reads only these fields. */
const review = (status: ReviewStatus, over: Partial<ReviewDetail> = {}): ReviewDetail =>
  ({
    id: 'review::TEST-WS/TEST-REPO#1',
    workspace: 'TEST-WS',
    slug: 'TEST-REPO',
    repo: 'TEST-REPO',
    pr: 1,
    htmlUrl: 'https://example.invalid/TEST-WS/TEST-REPO/pull/1',
    providerType: 'github',
    status,
    archivedAt: null,
    ...over,
  }) as unknown as ReviewDetail;

/**
 * Renders and waits for the session to resolve before anything is asserted.
 *
 * <p>Archive is the anchor: it is admin-only like Re-run but status-independent, so its arrival
 * proves the admin gate has opened. Without it, every "Re-run is absent" case would pass on the
 * first paint, before `useMe` has answered and while no button exists at all.
 */
async function renderActions(status: ReviewStatus) {
  render(<ReviewActions review={review(status)} onChanged={vi.fn()} />);
  await screen.findByRole('button', { name: /archive review/i });
}

const rerunButton = () => screen.queryByRole('button', { name: /re-run review/i });

/**
 * Total over `ReviewStatus` on purpose. It cannot catch a status the backend has and `api.ts` does
 * not — the failure that produced all three of these defects — but it fails to compile the moment
 * the union grows, which is the one moment TypeScript can force the decision to be made.
 */
const OFFERS_RERUN: Record<ReviewStatus, boolean> = {
  reviewing: false,
  completed: true,
  failed: true,
  refused: true,
  cancelled: false,
  superseded: false,
  observed: false,
};

describe('ReviewActions — which statuses offer Re-run', () => {
  beforeEach(() => {
    vi.spyOn(auth, 'fetchMe').mockResolvedValue(ADMIN);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('offers Re-run on a refused review — the cap was raised, and this is the retry', async () => {
    await renderActions('refused');

    expect(rerunButton()).toBeInTheDocument();
  });

  it.each(Object.entries(OFFERS_RERUN))('pins %s → Re-run offered: %s', async (status, offered) => {
    await renderActions(status as ReviewStatus);

    if (offered) {
      expect(rerunButton()).toBeInTheDocument();
    } else {
      expect(rerunButton()).not.toBeInTheDocument();
    }
  });

  it('withdraws Re-run from an archived refused review, as it does from every other status', async () => {
    // Archiving retires the pull request and the API answers 409 — the one gate that is not
    // status-keyed must keep winning over the status that now permits a re-run.
    render(<ReviewActions review={review('refused', { archivedAt: '2026-08-09T01:00:00Z' })} onChanged={vi.fn()} />);
    await screen.findByRole('button', { name: /unarchive review/i });

    expect(rerunButton()).not.toBeInTheDocument();
  });
});

describe('ReviewActions — the Re-run confirmation on a refused review', () => {
  beforeEach(() => {
    vi.spyOn(auth, 'fetchMe').mockResolvedValue(ADMIN);
  });

  it('names the cap and the recovery step, without claiming a previous result is discarded', async () => {
    await renderActions('refused');
    fireEvent.click(screen.getByRole('button', { name: /re-run review/i }));
    const dialog = await screen.findByRole('dialog');

    // The copy an operator needs: pressing this again with the limit unchanged spends nothing and
    // changes nothing, which is not obvious from a button labelled "Re-run".
    expect(within(dialog).getByText(/refused by a cap/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/Settings/)).toBeInTheDocument();

    // Nothing may claim a previous run's output is replaced or thrown away: a refusal made no model
    // call, so there is no previous result to discard.
    expect(within(dialog).queryByText(/discard|replace|delete/i)).not.toBeInTheDocument();
  });

  it('keeps the cap wording off every other status', async () => {
    await renderActions('completed');
    fireEvent.click(screen.getByRole('button', { name: /re-run review/i }));
    const dialog = await screen.findByRole('dialog');

    expect(within(dialog).queryByText(/refused by a cap/i)).not.toBeInTheDocument();
    expect(within(dialog).getByText(/not removed/i)).toBeInTheDocument();
  });
});
