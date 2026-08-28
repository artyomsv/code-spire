import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import type { ReviewSummary } from '../api';
import { ago, archivedBadge, CopyableValue, findCell, llmIcon, miniPipeline, prStateBadge, providerBadge, shortSha, statusCell } from '../render';
import { formatCost } from '../money';
import ReviewsFilters, { matchesChip, needsAttention as wantsAttention, type ChipFilter } from './ReviewsFilters';

function isSameDay(iso: string, now: number): boolean {
  const d = new Date(Date.parse(iso));
  const n = new Date(now);
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
}

/** The Cost cell's text + tooltip, computed once per row (it's read twice in the row markup). */
interface CostCell {
  text: string;
  title: string;
}

/**
 * `costMillicents` sums to 0 in three different situations that must not all render the same way:
 * no charge has landed yet, every priced charge was an asserted UNMETERED zero, and every charge
 * that landed came back UNKNOWN (so there is nothing to sum in the first place). `model` is blank
 * exactly when no charge line has landed for this review yet (derived from the ledger's most recent
 * charge, per its own contract above), telling "nothing to show" apart from "a real, possibly-zero,
 * total". `unpricedCalls` marks a real total partial — but when the total is ALSO zero, a dollar
 * figure would read as "confirmed free", which is exactly the state this branch exists to flag.
 */
export function costCell(r: ReviewSummary): CostCell {
  if (!r.model) return { text: '—', title: 'No charges recorded yet' };
  if (r.unpricedCalls > 0) {
    const reason = `${r.unpricedCalls} call(s) could not be priced`;
    if (r.costMillicents === 0) return { text: '— (unpriced)', title: reason };
    return { text: `${formatCost(r.costMillicents)}*`, title: `${reason} — this total is partial` };
  }
  return { text: formatCost(r.costMillicents), title: 'Review cost' };
}

interface Props {
  reviews: ReviewSummary[];
  loading: boolean;
  error: string | null;
  showArchived: boolean;
  /**
   * Reported upward rather than handled here: this list never fetches — it renders the rows it is
   * given — and including archived rows is a different request, not a different filter over the
   * same ones. The state belongs where the fetch is.
   */
  onShowArchivedChange: (show: boolean) => void;
}

export default function ReviewsList({ reviews, loading, error, showArchived, onShowArchivedChange }: Props) {
  const navigate = useNavigate();
  const [filter, setFilter] = useState<ChipFilter>('all');
  const [query, setQuery] = useState('');

  // Gentle "live" feel: tick `now` each second so relative times keep ticking.
  // Kept as state (not Date.now() per render) so the memos below stay effective.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const summary = useMemo(() => {
    const inFlight = reviews.filter((r) => r.status === 'reviewing').length;
    // Through the same predicate as the chip: a run that produced nothing did not review anything
    // today, and counting it here while the Completed chip excludes it makes one tile disagree with
    // the chip directly beneath it.
    const completedToday = reviews.filter(
      (r) => matchesChip(r.status, 'completed', r.degraded) && isSameDay(r.updatedAt, now),
    ).length;
    const needsAttention = reviews.filter((r) => wantsAttention(r.status, r.degraded)).length;
    return { inFlight, completedToday, needsAttention };
  }, [reviews, now]);

  /**
   * Every count goes through {@link matchesChip}, the same predicate the rows are filtered by.
   *
   * <p>They used to be independent one-liners, and they drifted the moment a status stopped mapping
   * to exactly one chip: a degraded review is `completed` but answers to Needs attention, so it was
   * counted by both and the chips summed to more rows than the list held.
   */
  const chipCounts = useMemo<Record<ChipFilter, number>>(() => {
    const count = (f: ChipFilter) => reviews.filter((r) => matchesChip(r.status, f, r.degraded)).length;
    return {
      all: count('all'),
      reviewing: count('reviewing'),
      completed: count('completed'),
      failed: count('failed'),
      closed: count('closed'),
    };
  }, [reviews]);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return reviews.filter((r) => {
      if (!matchesChip(r.status, filter, r.degraded)) return false;
      if (!q) return true;
      return (
        r.repo.toLowerCase().includes(q) ||
        String(r.pr).includes(q) ||
        r.author.toLowerCase().includes(q) ||
        r.sha.toLowerCase().includes(q) ||
        r.title.toLowerCase().includes(q) ||
        r.branch.toLowerCase().includes(q)
      );
    });
  }, [reviews, filter, query]);

  const open = (r: ReviewSummary) => navigate(`/r/${r.workspace}/${r.slug}/${r.pr}`);

  return (
    <section className="content" id="view-list">
      <div className="summary">
        <div className="stat s-live">
          <div className="k">In flight</div>
          <div className="v tnum" id="s-live">
            {summary.inFlight}
          </div>
        </div>
        <div className="stat s-good">
          <div className="k">Completed · today</div>
          <div className="v tnum">{summary.completedToday}</div>
        </div>
        <div className="stat s-crit">
          <div className="k">Needs attention</div>
          <div className="v tnum">{summary.needsAttention}</div>
        </div>
        <div className="stat s-neutral">
          <div className="k">Median review</div>
          <div className="v tnum">—</div>
        </div>
      </div>

      <ReviewsFilters
        query={query}
        onQueryChange={setQuery}
        filter={filter}
        onFilterChange={setFilter}
        counts={chipCounts}
        showArchived={showArchived}
        onShowArchivedChange={onShowArchivedChange}
      />

      <div className="tablewrap">
        <div className="thead">
          <div>Status</div>
          <div className="h-state">PR</div>
          <div className="h-prov">Provider</div>
          <div>Pull request</div>
          <div className="h-author">Author</div>
          <div className="h-title">Title</div>
          <div className="h-commit">Commit</div>
          <div className="h-mini">Pipeline</div>
          <div className="cell-r">Findings</div>
          <div className="h-model">Model</div>
          <div className="cell-r">Cost</div>
          <div className="cell-r">Updated</div>
        </div>
        <div id="rows">
          {error ? (
            <div style={{ padding: '26px 18px', color: 'var(--crit)', fontSize: 13 }}>{error}</div>
          ) : loading && reviews.length === 0 ? (
            <div style={{ padding: '26px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
          ) : (
            rows.map((r) => {
              const cost = costCell(r);
              return (
                <div
                  key={r.id}
                  className={`row ${r.status === 'superseded' || r.archivedAt ? 'faded' : ''}`}
                  data-id={r.id}
                  tabIndex={0}
                  role="button"
                  onClick={() => open(r)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') open(r);
                  }}
                >
                  <div className="status-cell">
                    {statusCell(r)}
                  </div>
                  <div className="state-cell">
                    {prStateBadge(r.prState)}
                  </div>
                  <div className="prov-cell">
                    {providerBadge(r) ?? <span className="prov-none">—</span>}
                  </div>
                  <div style={{ minWidth: 0 }}>
                    <div className="repo">
                      {r.repo}
                      <span className="pr">#{r.pr}</span>
                      {archivedBadge(r.archivedAt)}
                    </div>
                    <div className="sub">
                      <CopyableValue text={r.branch} copyTitle="Copy branch" />
                    </div>
                  </div>
                  <div className="author-cell" title={r.authorId ? `@${r.author} · ${r.authorId}` : `@${r.author}`}>
                    <div className="mono ellip">@{r.author}</div>
                    {r.authorId && <div className="sub mono ellip">{r.authorId}</div>}
                  </div>
                  <div className="title-cell">
                    <CopyableValue text={r.title} copyTitle="Copy title" />
                  </div>
                  <div className="commit-cell">
                    <CopyableValue text={r.sha} display={shortSha(r.sha)} mono copyTitle="Copy commit hash" />
                  </div>
                  <div className="cell-mini">{miniPipeline(r.status, r.stage)}</div>
                  <div className="cell-r">{findCell(r.status, r.findings, r.carriedOverFindings, r.degraded)}</div>
                  <div className="model-cell">{llmIcon(r.model, r.llmType)}</div>
                  <div className="cell-r">
                    <span className="mono" title={cost.title}>{cost.text}</span>
                  </div>
                  <div className="cell-r">
                    <span className="time">{ago(r.updatedAt)}</span>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </section>
  );
}
