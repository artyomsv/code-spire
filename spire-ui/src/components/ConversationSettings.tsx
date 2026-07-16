import { useEffect, useState } from 'react';
import {
  getConversationSettings,
  setConversationSettings,
  type ConversationLevel,
  type ConversationSettings as ConversationSettingsShape,
} from '../api';

const LEVELS: ConversationLevel[] = ['REPORT_ONLY', 'EXPLAIN', 'INTERACTIVE'];

const LABELS: Record<ConversationLevel, string> = {
  REPORT_ONLY: 'Report-only — post findings, ignore replies',
  EXPLAIN: 'Explain — answer and defend findings in-thread',
  INTERACTIVE: 'Interactive — can be convinced (Plan 2)',
};

/** Coerce any value to a valid level; unknown falls back to REPORT_ONLY (the fail-safe default). */
export function normalizeLevel(value: unknown): ConversationLevel {
  const v = String(value).trim().toUpperCase();
  return v === 'EXPLAIN' || v === 'INTERACTIVE' ? (v as ConversationLevel) : 'REPORT_ONLY';
}

/**
 * Conversation tuning: global default level, turn cap, retry attempts and backoff.
 * Reads on mount (GET /api/settings/conversation) and writes on Save (PUT, full
 * replace) — not per-keystroke, since the backend clamps out-of-range values and
 * we want to show the saved (clamped) result rather than fight the user's typing.
 */
export default function ConversationSettings() {
  const [settings, setSettings] = useState<ConversationSettingsShape | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let alive = true;
    getConversationSettings()
      .then((s) => alive && setSettings(s))
      .catch((err) => alive && setError(err instanceof Error ? err.message : String(err)));
    return () => {
      alive = false;
    };
  }, []);

  function update<K extends keyof ConversationSettingsShape>(key: K, value: ConversationSettingsShape[K]) {
    setSettings((prev) => (prev ? { ...prev, [key]: value } : prev));
    setSaved(false);
  }

  async function save() {
    if (!settings || busy) return;
    setBusy(true);
    setError(null);
    setSaved(false);
    try {
      const r = await setConversationSettings(settings);
      setSettings(r);
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
      <label className="field">
        <span>Interaction level</span>
        <select
          value={settings.level}
          disabled={busy}
          onChange={(e) => update('level', normalizeLevel(e.target.value))}
        >
          {LEVELS.map((l) => (
            <option key={l} value={l}>
              {LABELS[l]}
            </option>
          ))}
        </select>
        <small className="field-hint">
          The default applied to every provider that doesn&apos;t set its own conversation level.
        </small>
      </label>

      <div className="field-row-2">
        <label className="field">
          <span>Turn cap</span>
          <input
            type="number"
            min={1}
            max={50}
            value={settings.turnCap}
            disabled={busy}
            onChange={(e) => update('turnCap', Number(e.target.value))}
          />
          <small className="field-hint">Max bot replies per thread.</small>
        </label>
        <label className="field">
          <span>Retry attempts</span>
          <input
            type="number"
            min={1}
            max={10}
            value={settings.maxAttempts}
            disabled={busy}
            onChange={(e) => update('maxAttempts', Number(e.target.value))}
          />
          <small className="field-hint">Attempts on a transient API failure before dead-lettering.</small>
        </label>
      </div>

      <div className="field-row-2">
        <label className="field">
          <span>Backoff base (ms)</span>
          <input
            type="number"
            min={100}
            max={60000}
            value={settings.backoffBaseMs}
            disabled={busy}
            onChange={(e) => update('backoffBaseMs', Number(e.target.value))}
          />
        </label>
        <label className="field">
          <span>Backoff factor</span>
          <input
            type="number"
            min={1}
            max={5}
            step={0.5}
            value={settings.backoffFactor}
            disabled={busy}
            onChange={(e) => update('backoffFactor', Number(e.target.value))}
          />
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
