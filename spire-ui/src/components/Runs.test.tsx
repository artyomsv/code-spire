import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Runs, { isRunUnfinished, runStatusLabel, runStatusPill } from './Runs';
import * as api from '../api';
import type { RunListEntry } from '../api';
import { RunSocket } from '../test/liveRuns';

beforeEach(() => {
  RunSocket.sockets = [];
  vi.stubGlobal('WebSocket', RunSocket);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

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
    agentStartedAt: '2026-09-04T10:01:00Z',
    endedAt: '2026-09-04T10:05:00Z',
    cost: { millicents: 4600 },
    prUrl: null,
    prError: null,
    ...overrides,
  };
}

describe('what came of proposing the branch', () => {
  /**
   * A run that pushed and could not be proposed stays `succeeded` — the work IS on the remote — so
   * the status column reads the same either way. Reported in review: both outcomes rendered
   * identically and the missing delivery step was discoverable only in SQL or a server log.
   */
  it('links the pull request a run opened', async () => {
    show([run({ prUrl: 'https://github.com/acme/app/pull/29' })]);

    const link = await screen.findByRole('link', { name: '#29' });
    expect(link).toHaveAttribute('href', 'https://github.com/acme/app/pull/29');
  });

  it('says a succeeded run was not proposed, and why on the hover', async () => {
    show([run({ prError: '403 Forbidden: pull requests are disabled' })]);

    const cell = await screen.findByText('not proposed');
    expect(screen.getByTitle('403 Forbidden: pull requests are disabled')).toBeInTheDocument();
    // The run itself did not fail: the branch is on the remote and the status must keep saying so.
    // Read from the ROW, because "Succeeded" is also one of the filter's options.
    const row = cell.closest('tr') as HTMLElement;
    expect(within(row).getByText('Succeeded')).toBeInTheDocument();
  });

  /** A fix run proposes nothing by design, so a dash — not an error, since nothing is missing. */
  it('shows a dash for a run that proposed nothing', async () => {
    show([run({ kind: 'FIX', reviewId: 'review::acme/app#7', findingRef: 'thread-1' })]);

    await screen.findByText('FIX');
    expect(screen.queryByText('not proposed')).not.toBeInTheDocument();
  });
});

function show(rows: RunListEntry[]) {
  vi.spyOn(api, 'getRuns').mockResolvedValue(rows);
  return render(
    <MemoryRouter>
      <Runs />
    </MemoryRouter>,
  );
}

describe('the runs screen', () => {
  it('shows a run that changes into the selected status without another fetch', async () => {
    const queued = run({ status: 'queued', agentStartedAt: null, endedAt: null });
    show([queued]);
    await screen.findByTitle(queued.runId);
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'running' } });
    expect(screen.queryByTitle(queued.runId)).toBeNull();
    RunSocket.push({ ...queued, status: 'running' });
    const row = screen.getByTitle(queued.runId).closest('tr') as HTMLElement;
    expect(within(row).getByText('Running')).toBeInTheDocument();
    expect(api.getRuns).toHaveBeenCalledTimes(1);
  });

  it('keeps nonmatching pushed kinds and statuses hidden', async () => {
    show([run()]);
    await screen.findByTitle(run().runId);
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'FIX' } });
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'running' } });
    RunSocket.push(run({ runId: 'build', status: 'running' }));
    RunSocket.push(run({ runId: 'fix', kind: 'FIX', status: 'queued' }));
    expect(screen.queryByRole('table')).toBeNull();
    RunSocket.push(run({ runId: 'fix', kind: 'FIX', status: 'running' }));
    expect(screen.getByTitle('fix')).toBeInTheDocument();
    expect(screen.queryByTitle('build')).toBeNull();
    RunSocket.push(run({ runId: 'fix', kind: 'FIX', status: 'succeeded' }));
    expect(screen.queryByTitle('fix')).toBeNull();
    expect(api.getRuns).toHaveBeenCalledTimes(1);
  });

  it('updates a visible runs status, spend and proposal from pushes', async () => {
    const queued = run({ status: 'queued', endedAt: null, cost: { millicents: null } });
    show([queued]);
    await screen.findByTitle(queued.runId);
    RunSocket.push({ ...queued, status: 'succeeded', cost: { millicents: 7000 }, prUrl: 'https://example.invalid/pr/42' });
    const row = screen.getByTitle(queued.runId).closest('tr') as HTMLElement;
    expect(within(row).getByText('Succeeded')).toBeInTheDocument();
    expect(within(row).getByText('$0.070')).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: '#42' })).toHaveAttribute('href', 'https://example.invalid/pr/42');
    expect(api.getRuns).toHaveBeenCalledTimes(1);
  });

  it('labels queue time honestly and includes queue wait in duration', async () => {
    show([run()]);

    const headers = await screen.findAllByRole('columnheader');
    const queued = headers.findIndex((header) => header.textContent === 'Queued');
    const duration = headers.findIndex((header) => header.textContent === 'Duration');
    expect(queued).toBeGreaterThanOrEqual(0);
    expect(duration).toBeGreaterThanOrEqual(0);
    expect(screen.queryByRole('columnheader', { name: 'Started' })).toBeNull();
    const cells = within(screen.getAllByRole('row')[1]).getAllByRole('cell');
    expect(cells[queued]).toHaveAttribute('title', '2026-09-04T10:00:00Z');
    expect(cells[duration]).toHaveTextContent(/^5m 0s$/);
  });

  it('leaves final duration unknown until the run ends', async () => {
    show([run({ status: 'queued', agentStartedAt: null, endedAt: null })]);

    const headers = await screen.findAllByRole('columnheader');
    const duration = headers.findIndex((header) => header.textContent === 'Duration');
    const cells = within(screen.getAllByRole('row')[1]).getAllByRole('cell');
    expect(cells[duration]).toHaveTextContent(/^—$/);
  });

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
    // Scoped to the COST cell, the last one on the row. Asserting "some dash exists" was satisfied
    // by whichever column happened to be empty -- once by the review column, and again by the
    // proposal column when that was added, each time leaving the cost free to render as it liked.
    show([run({ status: 'running', kind: 'FIX', reviewId: 'review::acme/web#412',
      cost: { millicents: null } })]);

    const row = (await screen.findByText('review::acme/web#412')).closest('tr') as HTMLElement;
    const cells = within(row).getAllByRole('cell');
    expect(cells[cells.length - 1]).toHaveTextContent('—');
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
