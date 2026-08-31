import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router';
import { BarChart3, TriangleAlert, UserRound } from 'lucide-react';
import {
  AnalyticsBreakdown,
  AnalyticsLens,
  AnalyticsTotals,
  MyActivity,
  fetchAnalyticsOverview,
  fetchAnalyticsRepo,
  fetchAnalyticsRepos,
  fetchMyActivity,
} from '../api';

/**
 * Three states, never two.
 *
 * An empty corpus, a failed load and (on the personal view) an unlinked identity say
 * three different things and send an operator to three different places. Collapsing
 * any of them into "no data" is the shape the ADR-025 `refused` incident took, where a
 * status the lookup did not know fell through into the reassuring branch and a refused
 * review rendered as five green segments under "done".
 */
type LoadState<T> =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; value: T };

function useLoaded<T>(load: () => Promise<T>, deps: unknown[]): LoadState<T> {
  const [state, setState] = useState<LoadState<T>>({ kind: 'loading' });
  useEffect(() => {
    let live = true;
    setState({ kind: 'loading' });
    load()
      .then((value) => live && setState({ kind: 'ready', value }))
      .catch((e: unknown) =>
        live && setState({ kind: 'error', message: e instanceof Error ? e.message : String(e) }),
      );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return state;
}

function percent(rate: number | null): string {
  return rate === null ? '—' : `${Math.round(rate * 100)}%`;
}

function number(value: number | null): string {
  return value === null ? '—' : String(Math.round(value * 10) / 10);
}

/** A cell with no data reads `—`, never `0` — zero is a measurement, absence is not. */
function Totals({ totals }: { totals: AnalyticsTotals }) {
  return (
    <div className="tiles" role="group" aria-label="Totals">
      <div className="tile">
        <span className="tile-value">{totals.findings}</span>
        <span className="tile-label">Findings</span>
      </div>
      <div className="tile">
        <span className="tile-value">{totals.reviews}</span>
        <span className="tile-label">Reviews</span>
      </div>
      <div className="tile">
        <span className="tile-value">{percent(totals.dismissalRate)}</span>
        <span className="tile-label">
          Dismissed{totals.judged === 0 ? ' (nothing judged yet)' : ` of ${totals.judged} judged`}
        </span>
      </div>
      <div className="tile">
        <span className="tile-value">{number(totals.medianRoundsToResolve)}</span>
        <span className="tile-label">Median rounds to fix</span>
      </div>
      {totals.suppressed > 0 && (
        <div className="tile">
          <span className="tile-value">{totals.suppressed}</span>
          <span className="tile-label">Hidden by preferences</span>
        </div>
      )}
    </div>
  );
}

function Breakdown({ rows }: { rows: AnalyticsBreakdown[] }) {
  if (rows.length === 0) {
    return (
      <p className="muted">
        No findings recorded yet. This record starts when a review runs — nothing was backfilled,
        so the history begins here rather than pretending to reach further back than it does.
      </p>
    );
  }
  return (
    <table className="table">
      <thead>
        <tr>
          <th>Kind</th>
          <th>Severity</th>
          <th>Raised</th>
          <th>Dismissed</th>
          <th>Fixed</th>
          <th>Not judged</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={`${row.severity}-${row.category ?? 'unlabelled'}`}>
            <td>
              {row.category ?? (
                <span className="muted" title="The model did not label this one — a customized review prompt does not ask for a category.">
                  unlabelled
                </span>
              )}
            </td>
            <td>{row.severity}</td>
            <td>{row.raised}</td>
            <td>{row.dismissed}</td>
            <td>{row.resolved}</td>
            <td>{row.unjudged}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Lens({ state }: { state: LoadState<AnalyticsLens> }) {
  if (state.kind === 'loading') return <p className="muted">Loading…</p>;
  if (state.kind === 'error') {
    return (
      <p className="error" role="alert">
        <TriangleAlert size={14} /> Could not load analytics — {state.message}
      </p>
    );
  }
  return (
    <>
      <Totals totals={state.value.totals} />
      <Breakdown rows={state.value.breakdown} />
    </>
  );
}

/** Deployment-wide numbers plus the repositories that have any. */
export function AnalyticsOverview() {
  const overview = useLoaded<AnalyticsLens>(fetchAnalyticsOverview, []);
  const repos = useLoaded<string[]>(fetchAnalyticsRepos, []);

  return (
    <section className="card">
      <h2>
        <BarChart3 size={16} /> Review analytics
      </h2>
      <Lens state={overview} />
      <h3>Repositories</h3>
      {repos.kind === 'ready' && repos.value.length === 0 && (
        <p className="muted">No repository has recorded findings yet.</p>
      )}
      {repos.kind === 'ready' && (
        <ul className="plain-list">
          {repos.value.map((repo) => (
            <li key={repo}>
              <Link to={`/analytics/${repo}`}>{repo}</Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export function AnalyticsRepo() {
  const { workspace = '', slug = '' } = useParams();
  const lens = useLoaded<AnalyticsLens>(
    () => fetchAnalyticsRepo(workspace, slug),
    [workspace, slug],
  );
  return (
    <section className="card">
      <h2>
        <BarChart3 size={16} /> {workspace}/{slug}
      </h2>
      <Lens state={lens} />
    </section>
  );
}

/**
 * The caller's own numbers.
 *
 * The unlinked branch is the reason this is its own screen: it must not render as an
 * empty chart. "You have done nothing" and "we do not know who you are" are different
 * facts, and only one of them is fixable by the operator reading it.
 */
export function MyAnalytics({ subject }: { subject?: string }) {
  const state = useLoaded<MyActivity>(fetchMyActivity, []);

  if (state.kind === 'loading') return <p className="muted">Loading…</p>;
  if (state.kind === 'error') {
    return (
      <p className="error" role="alert">
        <TriangleAlert size={14} /> Could not load your activity — {state.message}
      </p>
    );
  }
  if (!state.value.linked) {
    return (
      <section className="card">
        <h2>
          <UserRound size={16} /> My activity
        </h2>
        <p role="status">
          Your SCM identity isn’t linked, so there is nothing to show yet — this is different from
          having no activity. Ask an admin to link you under Settings → Operators.
        </p>
        {subject && (
          <p className="muted">
            They will need your operator id: <code>{subject}</code>
          </p>
        )}
      </section>
    );
  }
  return (
    <section className="card">
      <h2>
        <UserRound size={16} /> My activity
      </h2>
      <p className="muted">
        {state.value.providerType} · {state.value.authorId}
      </p>
      {state.value.totals && <Totals totals={state.value.totals} />}
      <Breakdown rows={state.value.breakdown} />
    </section>
  );
}
