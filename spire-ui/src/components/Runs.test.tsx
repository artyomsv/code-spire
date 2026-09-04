import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Runs, { isRunUnfinished, runStatusLabel, runStatusPill } from './Runs';
import * as api from '../api';
import type { RunListEntry } from '../api';

/**
 * The factory's first screen.
 *
 * <p>The assertions that matter most here are about a status the UI's type system cannot see. This
 * repository's recorded trap is exactly that: `ReviewStatus` is a compile-time union and the value
 * arrives as runtime JSON, so an unlisted one fell into the SUCCESS branch — `refused` rendered as
 * five green segments, and a degraded run as "✓ clean".
 */

function run(overrides: Partial<RunListEntry> = {}): RunListEntry {
  return {
    runId: 'run::github:acme/app:subject:1',
    status: 'succeeded',
    kind: 'BUILD',
    harness: 'codex',
    model: 'TEST-MODEL',
    branch: 'spire/subject',
    pushedRef: 'spire/subject',
    reviewId: null,
    findingRef: null,
    failureCause: null,
    startedAt: '2026-09-04T10:00:00Z',
    endedAt: '2026-09-04T10:05:00Z',
    cost: { millicents: 4600 },
    ...overrides,
  };
}

function show(rows: RunListEntry[]) {
  vi.spyOn(api, 'getRuns').mockResolvedValue(rows);
  return render(
    <MemoryRouter>
      <Runs />
    </MemoryRouter>,
  );
}

describe('the runs screen', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('lists a run with its kind, status and cost', async () => {
    show([run()]);

    expect(await screen.findByText('Succeeded')).toBeTruthy();
    expect(screen.getByText('BUILD')).toBeTruthy();
    expect(screen.getByText('$0.046')).toBeTruthy();
  });

  /**
   * <b>A status this build has never heard of is named, not rendered blank.</b>
   *
   * <p>Driven with a value deliberately absent from `RunStatus` and cast at the boundary, which is
   * exactly how one arrives in production: the union is a compile-time claim about runtime JSON. A
   * `Record` lookup on an unlisted key is `undefined` however exhaustive it looks, and rendering that
   * as an empty cell reads as "nothing wrong" — which is the failure this project has already paid
   * for twice.
   */
  it('names a status it does not recognise rather than showing nothing', async () => {
    show([run({ status: 'awaiting_operator' as RunListEntry['status'] })]);

    expect(await screen.findByText('Unknown (awaiting_operator)')).toBeTruthy();
  });

  it('gives an unknown status the warning pill, never the completed one', () => {
    expect(runStatusPill('awaiting_operator')).toBe('refused');
    expect(runStatusPill('succeeded')).toBe('completed');
    expect(runStatusLabel('awaiting_operator')).toContain('awaiting_operator');
  });

  /**
   * And it is not claimed to be still running either.
   *
   * <p>Both directions are wrong for an unknown value, but they are wrong differently: "busy" makes a
   * finished run spin forever, and "ok" makes a broken one look clean. Finished-but-flagged is the
   * one that leads an operator to look.
   */
  it('does not claim an unknown status is still running', () => {
    expect(isRunUnfinished('queued')).toBe(true);
    expect(isRunUnfinished('running')).toBe(true);
    expect(isRunUnfinished('awaiting_operator')).toBe(false);
    expect(isRunUnfinished('succeeded')).toBe(false);
  });

  /**
   * <b>An unknown cost is an em dash, never a zero.</b>
   *
   * <p>ADR-023 all the way to the screen: the server refuses to collapse "nobody knows" into zero,
   * and this is the last place it could be undone. A `$0.000` beside runs that really were free is
   * the conflation the charge ledger was built to remove.
   */
  it('renders an unknown cost as a dash rather than as free', async () => {
    // A FIX run, so the review column renders a LINK rather than its own dash -- leaving the
    // cost cell as the only em dash on the row. With a build run this asserted 'some dash exists',
    // which the empty review column satisfies whatever the cost renders as.
    show([run({ status: 'running', kind: 'FIX', reviewId: 'review::acme/web#412',
      cost: { millicents: null } })]);

    expect(await screen.findByText('—')).toBeTruthy();
    expect(screen.queryByText('$0.000')).toBeNull();
  });

  /** And a real zero still shows as zero — an UNMETERED model costs nothing and that is a fact. */
  it('renders a known zero as zero', async () => {
    show([run({ cost: { millicents: 0 } })]);

    expect(await screen.findByText('$0.000')).toBeTruthy();
  });

  /** A fix run links to the review it came from; that link is the whole point of the join. */
  it('links a fix run to its review', async () => {
    show([run({ kind: 'FIX', reviewId: 'review::acme/web#412', findingRef: 'thread-aaa' })]);

    const link = await screen.findByText('review::acme/web#412');
    expect(link.closest('a')?.getAttribute('href')).toContain('/r/acme/web/412');
  });

  /**
   * A GitLab workspace can itself contain slashes, so the review id is parsed rather than split.
   *
   * <p>A naive split would send the operator to a page that does not exist, which is worse than not
   * linking at all — a dead link looks like a bug in the review, not in the link.
   */
  it('links a nested workspace correctly', async () => {
    show([run({ kind: 'FIX', reviewId: 'review::acme/platform/team/app#7' })]);

    const link = await screen.findByText('review::acme/platform/team/app#7');
    expect(link.closest('a')?.getAttribute('href')).toContain('/r/acme/platform/team/app/7');
  });

  /** A build run has no review, and says so with a dash rather than an empty cell. */
  it('shows no review for a build run', async () => {
    show([run()]);

    await screen.findByText('Succeeded');
    expect(screen.queryByText(/^review::/)).toBeNull();
  });

  it('says so when there are no runs at all', async () => {
    show([]);

    expect(await screen.findByText(/No runs yet/)).toBeTruthy();
  });

  /** A failed load is reported, not rendered as an empty list that reads as "nothing is running". */
  it('reports a load failure rather than showing an empty list', async () => {
    vi.spyOn(api, 'getRuns').mockRejectedValue(new Error('Failed to load runs'));

    render(
      <MemoryRouter>
        <Runs />
      </MemoryRouter>,
    );

    expect(await screen.findByText('Failed to load runs')).toBeTruthy();
    await waitFor(() => expect(screen.queryByText(/No runs yet/)).toBeNull());
  });

  /**
   * Every status in the union is offered as a filter.
   *
   * <p>Derived from the same map the table renders, so a status added to one and not the other is
   * impossible rather than merely unlikely — the two-encodings shape this project keeps paying for.
   */
  it('offers every known status as a filter option', async () => {
    show([run()]);
    await screen.findByText('Succeeded');

    const options = Array.from(
      (screen.getByLabelText('Status') as HTMLSelectElement).options,
    ).map((o) => o.value);

    expect(options).toContain('push_gate_refused');
    expect(options).toContain('delivered_nothing');
    expect(options).toContain('dispatch_uncertain');
    expect(options.filter((v) => v !== '')).toHaveLength(9);
  });
});
