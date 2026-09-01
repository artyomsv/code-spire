import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { BarChart3, TriangleAlert, UserRound } from 'lucide-react';
import {
  AnalyticsBreakdown,
  AnalyticsLens,
  AnalyticsRepository,
  AnalyticsTotals,
  MyActivity,
  fetchAnalyticsOverview,
  fetchAnalyticsRepo,
  fetchAnalyticsRepos,
  fetchMyActivity,
} from '../api';
import { ConnectOptions, connectOutcome } from './ConnectOptions';

/**
 * Three states, never two.
 *
 * An empty corpus, a failed load and (on the personal view) an unlinked identity say three different
 * things and send an operator to three different places. Collapsing any of them into "no data" is the
 * shape the ADR-025 `refused` incident took, where a status the lookup did not know fell through into
 * the reassuring branch and a refused review rendered as five green segments under "done".
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
      .catch(
        (e: unknown) =>
          live && setState({ kind: 'error', message: e instanceof Error ? e.message : String(e) }),
      );
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return state;
}

/** A cell with no measurement reads `—`, never `0` — zero is a number, absence is not. */
function percent(rate: number | null): string {
  return rate === null ? '—' : `${Math.round(rate * 100)}%`;
}

function rounded(value: number | null): string {
  return value === null ? '—' : String(Math.round(value * 10) / 10);
}

function Stat({ value, label }: { value: string; label: string }) {
  return (
    <div className="an-stat">
      <div className="an-stat-value">{value}</div>
      <div className="an-stat-label">{label}</div>
    </div>
  );
}

function Totals({ totals }: { totals: AnalyticsTotals }) {
  return (
    <div className="an-stats">
      <Stat value={String(totals.findings)} label="Findings" />
      {/* Counted from the FINDINGS table, so this is reviews that recorded findings -- not every
          review the deployment has run. Labelled "Reviews" it read 0 on a deployment with dozens of
          them, whose author name is on screen elsewhere, and the number was right while the label
          was a promise it never made. */}
      <Stat value={String(totals.reviews)} label="Reviews with findings" />
      <Stat
        value={percent(totals.dismissalRate)}
        label={totals.judged === 0 ? 'Dismissed (nothing judged yet)' : `Dismissed of ${totals.judged} judged`}
      />
      <Stat value={rounded(totals.medianRoundsToResolve)} label="Median rounds to fix" />
      {totals.suppressed > 0 && (
        <Stat value={String(totals.suppressed)} label="Hidden by preferences" />
      )}
    </div>
  );
}

function Breakdown({ rows }: { rows: AnalyticsBreakdown[] }) {
  if (rows.length === 0) {
    return (
      <p className="prov-note">
        No findings recorded yet, and earlier reviews are deliberately not counted here. Each review
        stores a single overwritten round of findings with no verdicts — enough for the count beside
        it on the reviews list, but not history: it cannot say what was raised in an earlier round,
        or which findings were fixed, dismissed or acknowledged. This record keeps one row per
        finding per round instead, and starts with the next review to run.
      </p>
    );
  }
  return (
    <table className="prov-table">
      <thead>
        <tr>
          <th>Kind</th>
          <th>Severity</th>
          <th className="cell-r">Raised</th>
          <th className="cell-r">Dismissed</th>
          <th className="cell-r">Fixed</th>
          <th className="cell-r">Not judged</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={`${row.severity}-${row.category ?? 'unlabelled'}`}>
            <td>
              {row.category ? (
                <span className="prov-name">{row.category}</span>
              ) : (
                <span
                  className="muted"
                  title="The model did not label this one — a customized review prompt does not ask for a category."
                >
                  unlabelled
                </span>
              )}
            </td>
            <td>
              <span className={`badge sev-${row.severity.toLowerCase()}`}>{row.severity}</span>
            </td>
            <td className="cell-r">{row.raised}</td>
            <td className="cell-r">{row.dismissed}</td>
            <td className="cell-r">{row.resolved}</td>
            <td className="cell-r">{row.unjudged}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Lens({ state }: { state: LoadState<AnalyticsLens> }) {
  if (state.kind === 'loading') return <p className="prov-note">Loading…</p>;
  if (state.kind === 'error') {
    return (
      <p className="prov-note an-error" role="alert">
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

export function AnalyticsOverview() {
  const overview = useLoaded<AnalyticsLens>(fetchAnalyticsOverview, []);
  const repos = useLoaded<AnalyticsRepository[]>(fetchAnalyticsRepos, []);

  return (
    <section className="content">
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">
            <BarChart3 size={15} className="an-title-icon" /> Review analytics
          </h2>
        </div>
        <Lens state={overview} />
      </div>

      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">Repositories</h2>
        </div>
        {repos.kind === 'ready' && repos.value.length === 0 && (
          <p className="prov-note">No repository has recorded findings yet.</p>
        )}
        {/* Counts beside each name, not names alone. A bare `owner/name` was read as a pull-request
            title on a real deployment; a count of reviews next to it is what makes it read as the
            repository those reviews belong to. */}
        {repos.kind === 'ready' && repos.value.length > 0 && (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Repository</th>
                <th className="cell-r">Reviews</th>
                <th className="cell-r">Findings</th>
              </tr>
            </thead>
            <tbody>
              {repos.value.map((row) => (
                <tr key={row.repo}>
                  <td>
                    <Link className="an-repo" to={`/analytics/${row.repo}`}>
                      {row.repo}
                    </Link>
                  </td>
                  <td className="cell-r">{row.reviews}</td>
                  <td className="cell-r">{row.findings}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
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
    <section className="content">
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">
            <BarChart3 size={15} className="an-title-icon" /> {workspace}/{slug}
          </h2>
          <Link className="btn-ghost" to="/analytics">
            All repositories
          </Link>
        </div>
        <Lens state={lens} />
      </div>
    </section>
  );
}

/**
 * The caller's own numbers, across every SCM account they own.
 *
 * The unlinked branch is why this is its own screen: it must not render as an empty chart. "You have
 * done nothing" and "we do not know who you are" are different facts, and only one is fixable by the
 * person reading it.
 */
export function MyAnalytics({ subject }: { subject?: string }) {
  const state = useLoaded<MyActivity>(fetchMyActivity, []);
  const [params] = useSearchParams();
  const accounts = state.kind === 'ready' && state.value.linked ? state.value.identities.length : null;
  // The sign-in returns here by a whole-window navigation, so its result arrives in the URL. It is
  // rendered above everything: an operator who has just come back from their SCM is looking for the
  // answer to one question, and a screen that silently looked the same either way would not answer it.
  const outcome = connectOutcome(params.get('connect'));

  return (
    <section className="content">
      {outcome && (
        <p className={`prov-note ${outcome.ok ? 'connect-ok' : 'an-error'}`} role="status">
          {outcome.text}
        </p>
      )}
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">
            <UserRound size={15} className="an-title-icon" /> My activity
          </h2>
          {accounts !== null && (
            <span className="badge">
              {accounts === 1 ? '1 linked account' : `${accounts} linked accounts`}
            </span>
          )}
        </div>
        <MyActivityBody state={state} subject={subject} />
      </div>
    </section>
  );
}

/** The three states, each its own answer -- see the note on {@link MyAnalytics}. */
function MyActivityBody({ state, subject }: { state: LoadState<MyActivity>; subject?: string }) {
  if (state.kind === 'loading') return <p className="prov-note">Loading…</p>;

  if (state.kind === 'error') {
    return (
      <p className="prov-note an-error" role="alert">
        <TriangleAlert size={14} /> Could not load your activity — {state.message}
      </p>
    );
  }

  if (!state.value.linked) {
    return (
      <div className="wh-empty" role="status">
        <div className="wh-empty-icon">
          <UserRound size={20} />
        </div>
        <p className="an-empty-title">Your SCM identity isn’t linked</p>
        <p className="prov-note">
          That is different from having no activity — nobody has told the dashboard which SCM account
          is yours. An admin can link you under <strong>Settings → Operators</strong>.
        </p>
        <ConnectOptions />
        {subject && (
          <p className="an-subject">
            Your operator id: <code>{subject}</code>
          </p>
        )}
      </div>
    );
  }

  return (
    <>
      <div className="an-identities">
        {state.value.identities.map((id) => (
          <span key={`${id.providerType}-${id.authorId}`} className="chip">
            {id.providerType} · {id.authorId}
          </span>
        ))}
      </div>
      <ConnectOptions />
      {state.value.totals && <Totals totals={state.value.totals} />}
      <Breakdown rows={state.value.breakdown} />
    </>
  );
}
