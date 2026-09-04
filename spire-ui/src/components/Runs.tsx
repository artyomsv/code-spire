import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { getRuns, type RunListEntry, type RunStatus } from '../api';
import { formatCost } from '../money';

/**
 * The factory's first screen.
 *
 * <p>Until now the factory had no UI at all — dispatch resolution and the credential pool were
 * `curl`, and every "which run…" question needed a database. That is what
 * `techdebt/spire-ui/4-3-the-factory-has-no-screens-at-all.md` records.
 *
 * <p><b>Built from the vocabulary this stylesheet already has</b> — `card`, `prov-table`, `pill`,
 * `wh-empty` — rather than a `runs-*` vocabulary of its own. The first draft invented five classes
 * and `styles.contract.test.ts` refused them, which is the check working: four screens once shipped
 * completely unstyled behind a fully green suite because they asked for classes nothing defined.
 *
 * <p><b>No prompt panel.</b> V43 leaves the dispatched prompt out of the read model deliberately: it
 * is a work item's text, it can quote source, and DATA-MODEL §5 keeps that class of content out of a
 * queryable read model. Showing it means storing it encrypted like `run_event.payload`, which is a
 * decision somebody has to make rather than a panel somebody adds.
 */

/**
 * How each status reads to a person, and which existing pill tone carries it.
 *
 * <p><b>Every value is listed, including the ones that are not failures but look like them.</b> The
 * recorded trap in this repository is a status the UI's type system cannot see falling into the
 * SUCCESS branch — `refused` once rendered as five green segments, a degraded run as "✓ clean". So
 * this map is exhaustive over the union AND every reader below handles a value absent from it.
 *
 * <p>The tones are the review pills' own (`completed`, `failed`, `cancelled`, `refused`,
 * `reviewing`), so a run reads in the same colour language as a review rather than in a private one.
 */
const STATUS: Record<RunStatus, { label: string; pill: string }> = {
  queued: { label: 'Queued', pill: 'reviewing' },
  running: { label: 'Running', pill: 'reviewing' },
  succeeded: { label: 'Succeeded', pill: 'completed' },
  failed: { label: 'Failed', pill: 'failed' },
  cancelled: { label: 'Cancelled', pill: 'cancelled' },
  // Not a failure of the RUN: the agent finished and produced work the gate would not publish.
  push_gate_refused: { label: 'Push refused', pill: 'refused' },
  dispatch_uncertain: { label: 'Dispatch unknown', pill: 'refused' },
  delivered_nothing: { label: 'Changed nothing', pill: 'refused' },
  delivered_unfinished: { label: 'Delivered unfinished', pill: 'refused' },
};

/**
 * A label for any status, including one this build has never heard of.
 *
 * <p>Exported for the test that drives a value deliberately absent from the union. A `Record` lookup
 * on an unlisted key is `undefined` at runtime however complete it looks at compile time, and
 * rendering that as an empty cell reads as "nothing wrong".
 */
export function runStatusLabel(status: string): string {
  return STATUS[status as RunStatus]?.label ?? `Unknown (${status})`;
}

/** And its pill. An unknown status warns — never `completed`, because silence is the bad direction. */
export function runStatusPill(status: string): string {
  return STATUS[status as RunStatus]?.pill ?? 'refused';
}

/** Whether a run is still going. An unknown status counts as finished — it is not claimed as busy. */
export function isRunUnfinished(status: string): boolean {
  return status === 'queued' || status === 'running';
}

/** A run id is long and the interesting half is the end; the middle is the repository. */
export function shortRunId(runId: string): string {
  const parts = runId.split(':');
  return parts.length >= 2 ? `${parts[parts.length - 2]}:${parts[parts.length - 1]}` : runId;
}

/**
 * The review's own page, or null when the id does not parse.
 *
 * <p>Parsed with a regex rather than split on `/`, because a GitLab workspace may itself contain
 * slashes. A naive split sends the operator to a page that does not exist, which is worse than not
 * linking: a dead link reads as a bug in the review rather than in the link.
 */
export function reviewPath(reviewId: string): string | null {
  const match = /^review::(.+)\/([^/]+)#(\d+)$/.exec(reviewId);
  return match ? `/r/${match[1]}/${match[2]}/${match[3]}` : null;
}

/**
 * What a run was FOR: a link to its review, the bare id, or an em dash.
 *
 * <p>Its own component because the three cases were a nested ternary, and because the middle one
 * is easy to read as an accident. A run can carry a review id this build cannot turn into a route
 * — an id recorded under an SCM the UI does not know a path for — and the id is still the most
 * useful thing to show, so it is shown unlinked rather than dropped. The em dash is the third
 * case and a different fact: a BUILD run was never for a review at all.
 */
function ReviewCell({ reviewId }: { reviewId: string | null }) {
  if (!reviewId) {
    return <span className="prov-sub">—</span>;
  }
  // Resolved ONCE. Called twice it was also narrowed twice, which is why the JSX needed the
  // `as string` cast the rest of this file does without.
  const path = reviewPath(reviewId);
  return path ? <Link to={path}>{reviewId}</Link> : <span className="mono">{reviewId}</span>;
}
export default function Runs() {
  const [runs, setRuns] = useState<RunListEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [kind, setKind] = useState('');
  const [status, setStatus] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    getRuns({ kind: kind || undefined, status: status || undefined })
      .then((rows) => {
        if (!cancelled) setRuns(rows);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [kind, status]);

  return (
    <div className="content">
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">Runs</h2>
          <div className="prov-actions">
            <select value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Kind">
              <option value="">All kinds</option>
              <option value="FIX">Fix</option>
              <option value="BUILD">Build</option>
            </select>
            <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status">
              <option value="">All statuses</option>
              {Object.keys(STATUS).map((value) => (
                <option key={value} value={value}>
                  {STATUS[value as RunStatus].label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {error && (
          <div style={{ padding: '26px 18px', color: 'var(--crit)', fontSize: 13 }}>{error}</div>
        )}

        {loading && !error && (
          <div className="prov-sub" style={{ padding: '26px 18px' }}>
            Loading runs…
          </div>
        )}

        {!loading && !error && runs.length === 0 && (
          <div className="wh-empty">
            <div className="wh-empty-title">No runs yet</div>
            <p className="wh-empty-text">
              A run appears here once one is dispatched — from a <code>/fix</code> comment on a
              review, or from the runs API.
            </p>
          </div>
        )}

        {!error && runs.length > 0 && (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Run</th>
                <th>Kind</th>
                <th>Status</th>
                <th>For</th>
                <th>Branch</th>
                <th className="cell-r">Cost</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => (
                <tr key={run.runId}>
                  <td>
                    <span className="mono" title={run.runId}>
                      {shortRunId(run.runId)}
                    </span>
                  </td>
                  <td>{run.kind}</td>
                  <td>
                    <span className={`pill ${runStatusPill(run.status)}`}>
                      <span className="glyph" />
                      {runStatusLabel(run.status)}
                    </span>
                    {run.failureCause && <div className="prov-sub">{run.failureCause}</div>}
                  </td>
                  <td>
                    <ReviewCell reviewId={run.reviewId} />
                  </td>
                  <td className="mono">{run.pushedRef ?? run.branch}</td>
                  {/*
                    formatCost renders null as an em dash, which is the whole point: unknown is not
                    zero, and a run still going has no charge yet. ADR-023 reaching the screen.
                  */}
                  <td className="cell-r">{formatCost(run.cost?.millicents ?? null)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
