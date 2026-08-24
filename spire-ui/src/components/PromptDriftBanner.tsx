import { AlertTriangle } from 'lucide-react';
import { diffLines } from '../promptDiff';

/** The six drift fields off `PromptView`, grouped so the banner takes one prop instead of six. */
export interface PromptDrift {
  baseKnown: boolean;
  defaultDrifted: boolean;
  currentDefaultSystem: string;
  currentDefaultBody: string;
  baseSystem: string | null;
  baseBody: string | null;
}

function DiffBlock({ title, before, after }: { title: string; before: string; after: string }) {
  if (before === after) return null;
  return (
    <div className="prompt-drift-diff">
      <div className="prompt-drift-diff-title">{title}</div>
      <pre className="prompt-drift-diff-body">
        {diffLines(before, after).map((line, idx) => (
          <div key={idx} className={`diff-line diff-${line.type}`}>
            <span className="diff-marker">{line.type === 'added' ? '+' : line.type === 'removed' ? '-' : ' '}</span>
            {line.text}
          </div>
        ))}
      </pre>
    </div>
  );
}

/**
 * Above the editor: what changed in the shipped default since this kind was customized, and the
 * two ways out. `resetPrompt` (Take the new default) discards the customization; `acceptPromptDefault`
 * (Keep mine) only re-stamps the ancestor, leaving the operator's text byte-identical. No auto-merge
 * -- the operator sees both sides and decides.
 *
 * <p>Renders nothing when there is nothing to report: `baseKnown && !defaultDrifted` covers both an
 * uncustomized kind and a customized-but-undrifted one.
 */
export default function PromptDriftBanner({
  drift, busy, onTakeDefault, onKeepMine,
}: {
  drift: PromptDrift;
  busy: boolean;
  onTakeDefault: () => void;
  onKeepMine: () => void;
}) {
  if (drift.baseKnown && !drift.defaultDrifted) return null;

  const actions = (
    <div className="prompt-drift-actions">
      <button type="button" className="btn-ghost" disabled={busy} onClick={onTakeDefault}>
        Take the new default
      </button>
      <button type="button" className="btn-ghost" disabled={busy} onClick={onKeepMine}>
        Keep mine
      </button>
    </div>
  );

  if (!drift.baseKnown) {
    return (
      <div className="prompt-drift prompt-drift-unknown">
        <p className="prompt-drift-muted">
          Customized before default tracking began — the original built-in text was not recorded,
          so there is nothing to compare against.
        </p>
        {actions}
      </div>
    );
  }

  return (
    <div className="prompt-drift">
      <div className="prompt-drift-banner">
        <AlertTriangle size={14} aria-hidden="true" />
        <span>The built-in prompt has changed since you customized this.</span>
      </div>
      <DiffBlock title="Instructions (system)" before={drift.baseSystem ?? ''} after={drift.currentDefaultSystem} />
      <DiffBlock title="Body" before={drift.baseBody ?? ''} after={drift.currentDefaultBody} />
      {actions}
    </div>
  );
}
