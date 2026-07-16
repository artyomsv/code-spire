import { useEffect, useState } from 'react';
import {
  getConversationDefault,
  setConversationDefault,
  type ConversationLevel,
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
 * The GLOBAL default conversation level. Providers inherit this unless they set their own override.
 * Reads on mount, writes optimistically (PUT /api/settings/conversation-level) with revert-on-error.
 */
export default function ConversationLevelSetting() {
  const [level, setLevel] = useState<ConversationLevel | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    getConversationDefault()
      .then((r) => alive && setLevel(normalizeLevel(r.level)))
      .catch((err) => alive && setError(err instanceof Error ? err.message : String(err)));
    return () => {
      alive = false;
    };
  }, []);

  async function choose(next: ConversationLevel) {
    if (busy || next === level) return;
    const prev = level;
    setBusy(true);
    setError(null);
    setLevel(next); // optimistic
    try {
      const r = await setConversationDefault(next);
      setLevel(normalizeLevel(r.level));
    } catch (err) {
      setLevel(prev); // revert
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="conv-default">
      <label className="field">
        <span>Global conversation level</span>
        <select
          value={level ?? ''}
          disabled={busy || level === null}
          onChange={(e) => choose(e.target.value as ConversationLevel)}
        >
          {level === null && <option value="">Loading…</option>}
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
      {error && <p className="prov-error">{error}</p>}
    </div>
  );
}
