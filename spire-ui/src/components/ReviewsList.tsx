import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { ReviewStatus, ReviewSummary } from '../api';
import { ago, CopyableValue, findCell, miniPipeline, pill, providerBadge, shortSha } from '../render';

type ChipFilter = 'all' | 'reviewing' | 'completed' | 'failed' | 'closed';

const CHIPS: { f: ChipFilter; label: string }[] = [
  { f: 'all', label: 'All' },
  { f: 'reviewing', label: 'Reviewing' },
  { f: 'completed', label: 'Completed' },
  { f: 'failed', label: 'Needs attention' },
  { f: 'closed', label: 'Closed' },
];

function isSameDay(iso: string, now: number): boolean {
  const d = new Date(Date.parse(iso));
  const n = new Date(now);
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
}

function matchesChip(status: ReviewStatus, f: ChipFilter): boolean {
  if (f === 'all') return true;
  if (f === 'reviewing') return status === 'reviewing';
  if (f === 'completed') return status === 'completed';
  if (f === 'failed') return status === 'failed';
  return status === 'cancelled' || status === 'superseded';
}

interface Props {
  reviews: ReviewSummary[];
  loading: boolean;
  error: string | null;
}

export default function ReviewsList({ reviews, loading, error }: Props) {
  const navigate = useNavigate();
  const [filter, setFilter] = useState<ChipFilter>('all');
  const [query, setQuery] = useState('');

  // Gentle "live" feel: re-render each second so relative times keep ticking.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);

  const now = Date.now();

  const summary = useMemo(() => {
    const inFlight = reviews.filter((r) => r.status === 'reviewing').length;
    const completedToday = reviews.filter((r) => r.status === 'completed' && isSameDay(r.updatedAt, now)).length;
    const needsAttention = reviews.filter((r) => r.status === 'failed').length;
    return { inFlight, completedToday, needsAttention };
  }, [reviews, now]);

  const chipCounts = useMemo<Record<ChipFilter, number>>(
    () => ({
      all: reviews.length,
      reviewing: reviews.filter((r) => r.status === 'reviewing').length,
      completed: reviews.filter((r) => r.status === 'completed').length,
      failed: reviews.filter((r) => r.status === 'failed').length,
      closed: reviews.filter((r) => r.status === 'cancelled' || r.status === 'superseded').length,
    }),
    [reviews],
  );

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return reviews.filter((r) => {
      if (!matchesChip(r.status, filter)) return false;
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

      <div className="filters">
        <div className="search">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
            <circle cx="7" cy="7" r="4.5" stroke="currentColor" strokeWidth="1.4" />
            <path d="M11 11l3 3" stroke="currentColor" strokeWidth="1.4" />
          </svg>
          <input
            placeholder="repo, PR #, author, sha…"
            aria-label="Filter reviews"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        <div className="chips" id="chips">
          {CHIPS.map((c) => (
            <span
              key={c.f}
              className={`chip ${filter === c.f ? 'on' : ''}`}
              data-f={c.f}
              onClick={() => setFilter(c.f)}
            >
              {c.label} <span className="n">{chipCounts[c.f]}</span>
            </span>
          ))}
        </div>
      </div>

      <div className="tablewrap">
        <div className="thead">
          <div>Status</div>
          <div className="h-prov">Provider</div>
          <div>Pull request</div>
          <div className="h-author">Author</div>
          <div className="h-title">Title</div>
          <div className="h-commit">Commit</div>
          <div className="h-mini">Pipeline</div>
          <div className="cell-r">Findings</div>
          <div className="cell-r">Updated</div>
        </div>
        <div id="rows">
          {error ? (
            <div style={{ padding: '26px 18px', color: 'var(--crit)', fontSize: 13 }}>{error}</div>
          ) : loading && reviews.length === 0 ? (
            <div style={{ padding: '26px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
          ) : (
            rows.map((r) => (
              <div
                key={r.id}
                className={`row ${r.status === 'superseded' ? 'faded' : ''}`}
                data-id={r.id}
                tabIndex={0}
                role="button"
                onClick={() => open(r)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') open(r);
                }}
              >
                <div>{pill(r.status)}</div>
                <div className="prov-cell">
                  {providerBadge(r) ?? <span className="prov-none">—</span>}
                </div>
                <div style={{ minWidth: 0 }}>
                  <div className="repo">
                    {r.repo}
                    <span className="pr">#{r.pr}</span>
                  </div>
                  <div className="sub">
                    <span>{r.branch}</span>
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
                <div className="cell-r">{findCell(r.status, r.findings)}</div>
                <div className="cell-r">
                  <span className="time">{ago(r.updatedAt)}</span>
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    </section>
  );
}
