import type { ReviewStatus } from '../api';

export type ChipFilter = 'all' | 'reviewing' | 'completed' | 'failed' | 'closed';

const CHIPS: { f: ChipFilter; label: string }[] = [
  { f: 'all', label: 'All' },
  { f: 'reviewing', label: 'Reviewing' },
  { f: 'completed', label: 'Completed' },
  { f: 'failed', label: 'Needs attention' },
  { f: 'closed', label: 'Closed' },
];

export function matchesChip(status: ReviewStatus, f: ChipFilter): boolean {
  if (f === 'all') return true;
  if (f === 'reviewing') return status === 'reviewing';
  if (f === 'completed') return status === 'completed';
  if (f === 'failed') return status === 'failed';
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
