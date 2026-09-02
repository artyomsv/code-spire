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
 * Every card shows the evidence AND the bar it cleared. That is not decoration: a proposal from
 * eleven data points is the ADR-026 rung-2 gate's failure recurring — a conclusion drawn from a
 * corpus too thin to speak, which nobody could see was thin because the numbers were never rendered.
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
    <tr>
      <td>
        <span className="prov-name">
          {preference.category} · {preference.severity}
        </span>
        <div className="prov-sub">
          {preference.pathGlob}
          {preference.scopeType === 'repo' && <> in {preference.scopeValue}</>}
        </div>
      </td>
      <td>
        <span className="mem-evidence">
          {preference.evidenceDismissed} of {preference.evidenceTotal} dismissed
        </span>
        <div className="prov-sub">
          {share}% across {preference.evidenceReviews}{' '}
          {preference.evidenceReviews === 1 ? 'pull request' : 'pull requests'} · bar:{' '}
          {thresholds.minEvidence} findings, {thresholds.minDismissedPercent}%
        </div>
      </td>
      <td className="cell-r">
        {preference.state === 'PROPOSED' && (
          <div className="prov-actions">
            <button className="btn" onClick={() => onDecide(preference.id, 'approve')}>
              <Check size={13} /> Approve
            </button>
            <button className="btn-ghost" onClick={() => onDecide(preference.id, 'reject')}>
              <X size={13} /> Reject
            </button>
          </div>
        )}
        {preference.state === 'APPROVED' && (
          <div className="prov-actions">
            <span className="badge mem-active">Hiding these findings</span>
            <button className="btn-ghost" onClick={() => onDecide(preference.id, 'revoke')}>
              <RotateCcw size={13} /> Stop hiding
            </button>
          </div>
        )}
        {preference.state === 'REJECTED' && <span className="badge muted">Rejected</span>}
      </td>
    </tr>
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
    <section className="content">
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">
            <Brain size={15} className="an-title-icon" /> Learned memory
          </h2>
          <button className="btn-ghost" onClick={() => void rescan()} disabled={busy}>
            {busy ? 'Scanning…' : 'Scan now'}
          </button>
        </div>

        <p className="prov-note">
          When a team keeps dismissing the same kind of finding, it is proposed here. An approved
          preference hides matching findings <em>after</em> the review runs — the count is shown on the
          pull request and the hidden findings stay on the review, so a preference that turns out wrong
          is visible and one click from being switched off. Security findings and blockers are never
          proposed, whatever the evidence says.
        </p>

        {error && (
          <p className="prov-note an-error" role="alert">
            {error}
          </p>
        )}

        {view !== null && view.preferences.length === 0 && (
          <div className="wh-empty" role="status">
            <div className="wh-empty-icon">
              <Brain size={20} />
            </div>
            <p className="an-empty-title">Nothing proposed yet</p>
            <p className="prov-note">
              A group needs {view.thresholds.minEvidence} judged findings across at least two pull
              requests before anything can be suggested, and the record only started when this shipped
              — nothing was backfilled. Findings with no category can never be grouped, so a repository
              using a customized review prompt will not produce proposals.
            </p>
          </div>
        )}

        {view !== null && view.preferences.length > 0 && (
          <table className="prov-table">
            <thead>
              <tr>
                <th>Preference</th>
                <th>Evidence</th>
                <th className="cell-r">Decision</th>
              </tr>
            </thead>
            <tbody>
              {view.preferences.map((preference) => (
                <Card
                  key={preference.id}
                  preference={preference}
                  thresholds={view.thresholds}
                  onDecide={(id, action) => void decide(id, action)}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  );
}
