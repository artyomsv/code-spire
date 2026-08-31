import { useEffect, useState } from 'react';
import { Brain, Check, RotateCcw, X } from 'lucide-react';
import {
  LearnedPreference,
  MemoryThresholds,
  decidePreference,
  fetchMemory,
  rescanMemory,
} from '../api';

/**
 * What the reviewer has learned to stop saying, and whether an operator agrees (P4 / FR-10).
 *
 * Every card shows the evidence AND the threshold it had to clear. That is not decoration:
 * a proposal from eleven data points is the ADR-026 rung-2 gate's failure recurring — a
 * conclusion drawn from a corpus too thin to speak, which nobody could see was thin because
 * the numbers were never on screen.
 */

function Card({
  preference,
  thresholds,
  onDecide,
}: {
  preference: LearnedPreference;
  thresholds: MemoryThresholds;
  onDecide: (id: number, action: 'approve' | 'reject' | 'revoke') => void;
}) {
  const share = preference.evidenceTotal
    ? Math.round((preference.evidenceDismissed / preference.evidenceTotal) * 100)
    : 0;

  return (
    <li className="pref-card">
      <p>
        <strong>
          {preference.evidenceDismissed} of {preference.evidenceTotal}
        </strong>{' '}
        <code>{preference.category}</code> findings at <code>{preference.severity}</code> under{' '}
        <code>{preference.pathGlob}</code>
        {preference.scopeType === 'repo' && <> in {preference.scopeValue}</>} were dismissed.
      </p>
      <p className="muted">
        {share}% dismissed across {preference.evidenceReviews}
        {preference.evidenceReviews === 1 ? ' pull request' : ' pull requests'} · threshold:{' '}
        {thresholds.minEvidence} findings, {thresholds.minDismissedPercent}% dismissed
      </p>
      {preference.state === 'PROPOSED' && (
        <div className="row-actions">
          <button onClick={() => onDecide(preference.id, 'approve')}>
            <Check size={14} /> Approve
          </button>
          <button onClick={() => onDecide(preference.id, 'reject')}>
            <X size={14} /> Reject
          </button>
        </div>
      )}
      {preference.state === 'APPROVED' && (
        <div className="row-actions">
          <span className="pill">Hiding these findings</span>
          <button onClick={() => onDecide(preference.id, 'revoke')}>
            <RotateCcw size={14} /> Stop hiding
          </button>
        </div>
      )}
      {preference.state === 'REJECTED' && <span className="pill pill--muted">Rejected</span>}
    </li>
  );
}

export function SettingsMemory() {
  const [view, setView] = useState<{
    preferences: LearnedPreference[];
    thresholds: MemoryThresholds;
  } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = () =>
    fetchMemory()
      .then(setView)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));

  useEffect(() => {
    void reload();
  }, []);

  const decide = async (id: number, action: 'approve' | 'reject' | 'revoke') => {
    setError(null);
    try {
      await decidePreference(id, action);
      await reload();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const rescan = async () => {
    setError(null);
    setBusy(true);
    try {
      await rescanMemory();
      await reload();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card">
      <h2>
        <Brain size={16} /> Learned memory
      </h2>
      <p className="muted">
        When a team keeps dismissing the same kind of finding, it is proposed here. An approved
        preference hides matching findings <em>after</em> the review runs — the count is shown on the
        pull request and the hidden findings stay on the review, so a preference that turns out wrong
        is visible and one click from being switched off.
      </p>
      <p className="muted">
        Findings with no category can never be grouped, so a repository using a customized review
        prompt — and any finding filed with <code>/finding</code> — will never produce a proposal.
      </p>

      <button onClick={() => void rescan()} disabled={busy}>
        {busy ? 'Scanning…' : 'Scan now'}
      </button>

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      {view !== null && view.preferences.length === 0 && (
        <p role="status">
          Nothing proposed yet. Preferences need {view.thresholds.minEvidence} judged findings in one
          group before anything can be suggested, and the record only started when this shipped —
          nothing was backfilled.
        </p>
      )}

      {view !== null && view.preferences.length > 0 && (
        <ul className="plain-list">
          {view.preferences.map((preference) => (
            <Card
              key={preference.id}
              preference={preference}
              thresholds={view.thresholds}
              onDecide={(id, action) => void decide(id, action)}
            />
          ))}
        </ul>
      )}
    </section>
  );
}
