import { useEffect, useState } from 'react';
import { getReviewMode, setReviewMode, type ReviewMode } from '../api';

/** Coerce any value to a valid mode; anything but 'observe' is 'active' (matches the backend). */
export function normalizeMode(value: unknown): ReviewMode {
  return String(value).trim().toLowerCase() === 'observe' ? 'observe' : 'active';
}

/**
 * Global observe/active switch. Reads the current mode on mount and flips it live
 * (PUT /api/settings/review-mode) — the orchestrator picks the new mode up on the
 * next PR event, no restart. Optimistic with revert-on-error.
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
    <div className="card">
      <div className="head">
        <h3>Review mode</h3>
        {mode && (
          <span className={`pill ${active ? 'completed' : 'observed'}`}>
            <span className="glyph"></span>
            {active ? 'Active' : 'Observe'}
          </span>
        )}
      </div>
      <div className="body">
        <div className="switch-row">
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
          <span className="switch-label">
            {mode === null ? 'Loading…' : active ? 'Actively reviewing' : 'Observing only'}
          </span>
        </div>
        <p className="switch-hint">
          <strong>Active</strong> runs the full pipeline and posts comments to PRs.{' '}
          <strong>Observe</strong> registers PRs on the dashboard but posts nothing — no diff
          fetch, no LLM call, no comments. Takes effect on the next PR event; no restart.
        </p>
        {error && <div className="modal-msg modal-error">{error}</div>}
      </div>
    </div>
  );
}
