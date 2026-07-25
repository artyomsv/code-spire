import { useEffect, useState } from 'react';
import { getReviewSettings, setReviewSettings, type ReviewSettings as ReviewSettingsShape } from '../api';

/**
 * The review pipeline's own tuning. Kept as its own group next to the conversation defaults because
 * the two retry budgets behave differently, and one shared "Retry attempts" field led to a review
 * stopping after 3 attempts while the visible setting said 5.
 *
 * Reads on mount (GET /api/settings/review) and writes on Save (PUT) — not per-keystroke, so the
 * backend's clamped value is what lands in the field rather than fighting the user's typing.
 */
export default function ReviewSettings() {
  const [settings, setSettings] = useState<ReviewSettingsShape | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let alive = true;
    getReviewSettings()
      .then((s) => alive && setSettings(s))
      .catch((err) => alive && setError(err instanceof Error ? err.message : String(err)));
    return () => {
      alive = false;
    };
  }, []);

  async function save() {
    if (!settings || busy) return;
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      setSettings(await setReviewSettings(settings));
      setSaved(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  if (!settings) {
    return (
      <div className="conv-default">
        {error ? <p className="prov-error">{error}</p> : <p className="prov-sub">Loading…</p>}
      </div>
    );
  }

  return (
    <div className="conv-default">
      <p className="settings-scope">
        These govern the <strong>review pipeline</strong> — fetching the diff, calling the model, posting
        the comments. A review that runs out of attempts is reported as failed on its own card, with the
        provider&apos;s error; it is never dead-lettered.
      </p>

      <div className="field-row-2">
        <label className="field">
          <span>Retry attempts</span>
          <input
            type="number"
            min={1}
            max={10}
            value={settings.maxAttempts}
            disabled={busy}
            onChange={(e) => {
              setSettings({ maxAttempts: Number(e.target.value) });
              setSaved(false);
            }}
          />
          <small className="field-hint">
            Attempts at reviewing a commit when the SCM or LLM fails transiently. Each attempt re-runs the
            whole pipeline from the diff fetch.
          </small>
        </label>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 4 }}>
        <button type="button" className="btn" disabled={busy} onClick={() => void save()}>
          {busy ? 'Saving…' : 'Save'}
        </button>
        {saved && !busy && <div className="modal-msg modal-ok">Saved</div>}
      </div>

      {error && <p className="prov-error">{error}</p>}
    </div>
  );
}
