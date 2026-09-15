import { phases, type Phase } from './workPolicyApi';

/**
 * Who decides a phase. `off` nobody runs it, `approve` waits for a person, and everything else —
 * `auto`, `draft_pr`, `pr`, `auto_if_green` — proceeds without one inside the profile's limits.
 */
function decider(mode: string): 'off' | 'ask' | 'auto' {
  if (mode === 'off') return 'off';
  return mode === 'approve' ? 'ask' : 'auto';
}

/**
 * The eight run phases as one row of coloured cells, each showing its phase initial. The same eight
 * values rendered as chips filled six lines of a table cell and could not be compared between rows;
 * colour carries the only question a reader has here — what still needs a person.
 *
 * <p>The cells are decoration: the strip itself names all eight phases and their modes, so a screen
 * reader hears the whole vector once instead of eight unexplained letters.
 */
export default function PhaseStrip({ modes }: { modes: Record<Phase, string> }) {
  const spoken = phases.map(phase => `${phase.toLowerCase()} ${modes[phase]}`).join(', ');
  return (
    <span className="phase-strip" role="img" aria-label={spoken}>
      {phases.map(phase => (
        <span key={phase} className={`ph phase-${decider(modes[phase])}`} aria-hidden="true"
          title={`${phase.toLowerCase()}: ${modes[phase]}`}>
          {phase[0]}
        </span>
      ))}
    </span>
  );
}

/** The key the strip needs to be readable. Render it once, under the table it explains. */
export function PhaseLegend() {
  return (
    <div className="phase-legend">
      <span><i className="phase-off" aria-hidden="true" />off — the phase never runs</span>
      <span><i className="phase-ask" aria-hidden="true" />approve — waits for a person</span>
      <span><i className="phase-auto" aria-hidden="true" />auto — runs within the limits</span>
    </div>
  );
}
