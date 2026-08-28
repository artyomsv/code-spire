import type { ReviewStatus } from '../api';

export type ChipFilter = 'all' | 'reviewing' | 'completed' | 'failed' | 'closed';

const CHIPS: { f: ChipFilter; label: string }[] = [
  { f: 'all', label: 'All' },
  { f: 'reviewing', label: 'Reviewing' },
  { f: 'completed', label: 'Completed' },
  { f: 'failed', label: 'Needs attention' },
  { f: 'closed', label: 'Closed' },
];

/**
 * Whether a status wants an operator to do something. The single definition behind all three
 * surfaces that say "needs attention": the chip's filter, the chip's count and the summary tile.
 * They were three separate literal comparisons, which is precisely how a chip comes to read 0 while
 * still opening onto rows — the same drift argument that gave the spend gates one `SpendGate`.
 *
 * <p>`refused` counts, and does not go to Closed. It is terminal like `cancelled` and `superseded`,
 * so Closed is the tempting bucket, but those two mean "nothing to do" and a refusal always leaves
 * the operator a decision: raise the cap, wait for the window, or split the pull request.
 *
 * <p>What settles it is the **diff-size** gate. The `CAP_REACHED` attention row comes from
 * `SpendGate.decide()`, which reads the spend and call caps only — a review refused for a diff too
 * large raises no attention row anywhere. Under Closed it would have no surface at all: not a row
 * in the panel, and not a chip anyone would think to open. Needs attention is the only place it can
 * be found, so both refusal kinds go there and the list agrees with the panel rather than
 * contradicting it.
 */
export function needsAttention(status: ReviewStatus, degraded = false): boolean {
  // A degraded run belongs here for the same reason a refused one does: it completed, so no other
  // chip would surface it, and it leaves the operator a decision (re-run, or raise the output cap).
  //
  // Gated on `completed`, mirroring the REVIEW_DEGRADED attention query. The flag outlives the run
  // that set it, so an unqualified test claimed a review being re-reviewed right now for BOTH
  // Reviewing and Needs attention — two chips for one row, and the counts stopped adding up.
  return status === 'failed' || status === 'refused' || (status === 'completed' && degraded);
}

/**
 * Which chip a status falls under. Every status must fall under exactly one besides All, or a row
 * exists that no chip can reach — which is what a refused review did before it was listed here.
 */
export function matchesChip(status: ReviewStatus, f: ChipFilter, degraded = false): boolean {
  if (f === 'all') return true;
  if (f === 'reviewing') return status === 'reviewing';
  // A degraded run is `completed` but produced nothing, so it answers to Needs attention rather
  // than Completed — otherwise the two chips would both claim it and the counts would not add up.
  if (f === 'completed') return status === 'completed' && !degraded;
  if (f === 'failed') return needsAttention(status, degraded);
  return status === 'cancelled' || status === 'superseded';
}

interface Props {
  query: string;
  onQueryChange: (query: string) => void;
  filter: ChipFilter;
  onFilterChange: (filter: ChipFilter) => void;
  counts: Record<ChipFilter, number>;
  showArchived: boolean;
  onShowArchivedChange: (show: boolean) => void;
}

/**
 * The reviews table's filter bar.
 *
 * <p>Show archived is deliberately NOT another chip. The chips select among the rows already on
 * screen; this one changes which rows are requested at all, and putting it in the same row of
 * controls with the same shape would say the opposite.
 */
export default function ReviewsFilters({
  query,
  onQueryChange,
  filter,
  onFilterChange,
  counts,
  showArchived,
  onShowArchivedChange,
}: Props) {
  return (
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
          onChange={(e) => onQueryChange(e.target.value)}
        />
      </div>
      <div className="chips" id="chips">
        {CHIPS.map((c) => (
          <button
            key={c.f}
            type="button"
            className={`chip ${filter === c.f ? 'on' : ''}`}
            data-f={c.f}
            aria-pressed={filter === c.f}
            onClick={() => onFilterChange(c.f)}
          >
            {c.label} <span className="n">{counts[c.f]}</span>
          </button>
        ))}
      </div>
      <label className="toggle-archived">
        <input
          type="checkbox"
          checked={showArchived}
          onChange={(e) => onShowArchivedChange(e.target.checked)}
        />
        Show archived
      </label>
    </div>
  );
}
