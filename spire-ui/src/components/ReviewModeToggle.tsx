import { useEffect, useState } from 'react';
import { getReviewMode, setReviewMode, type ReviewMode } from '../api';

/** Coerce any value to a valid mode; anything but 'observe' is 'active' (matches the backend). */
export function normalizeMode(value: unknown): ReviewMode {
  return String(value).trim().toLowerCase() === 'observe' ? 'observe' : 'active';
}

const MODE_INFO =
  'Active runs the full pipeline and posts comments to PRs. ' +
  'Observe registers PRs on the dashboard but posts nothing — no diff fetch, no LLM call, no comments. ' +
  'Takes effect on the next PR event; no restart.';

/**
 * Global observe/active switch, docked at the bottom of the sidebar. Reads the
 * current mode on mount and flips it live (PUT /api/settings/review-mode) — the
 * orchestrator picks the new mode up on the next PR event, no restart. Optimistic
 * with revert-on-error.
 */
export default function ReviewModeToggle() {
  const [mode, setMode] = useState<ReviewMode | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    getReviewMode()
      .then((r) => alive && setMode(normalizeMode(r.mode)))
      .catch((err) => alive && setError(err instanceof Error ? err.message : String(err)));
    return () => {
      alive = false;
    };
  }, []);

  async function toggle(next: ReviewMode) {
    if (busy || next === mode) return;
    const prev = mode;
    setBusy(true);
    setError(null);
    setMode(next); // optimistic
    try {
      const r = await setReviewMode(next);
      setMode(normalizeMode(r.mode));
    } catch (err) {
      setMode(prev); // revert
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  const active = mode === 'active';

  return (
    <div className="rail-mode">
      <div className="rail-mode-top">
        <span className="rail-mode-title">Review mode</span>
        <span className="info" tabIndex={0} role="note" aria-label={MODE_INFO}>
          <svg viewBox="0 0 16 16" width="13" height="13" fill="none" aria-hidden="true">
            <circle cx="8" cy="8" r="6.3" stroke="currentColor" strokeWidth="1.3" />
            <path d="M8 7.2v3.4M8 5.1v0.01" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
          <span className="info-pop" role="tooltip">{MODE_INFO}</span>
        </span>
      </div>
      <div className="rail-mode-row">
        <label className="switch">
          <input
            type="checkbox"
            role="switch"
            aria-label="Active reviewing"
            checked={active}
            disabled={busy || mode === null}
            onChange={(e) => toggle(e.target.checked ? 'active' : 'observe')}
          />
          <span className="switch-track">
            <span className="switch-thumb"></span>
          </span>
        </label>
        <span className={`rail-mode-state ${mode === null ? '' : active ? 'on' : 'off'}`}>
          {mode === null ? 'Loading…' : active ? 'Active' : 'Observe'}
        </span>
      </div>
      {error && <div className="rail-mode-err">{error}</div>}
    </div>
  );
}
