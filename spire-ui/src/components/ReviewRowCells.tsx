import type { ReviewStatus } from '../api';
import { STAGES, stageLabel } from '../render';
import Tooltip from './Tooltip';

/** The five steps a list row draws; "Done" is the state of the last, not a sixth segment. */
const ROW_STEPS = STAGES.slice(0, 5);

/**
 * Statuses that ran no step worth drawing as progress: every segment takes the status's colour and word. A refused
 * review once drew five green segments; drawing how far a refusal got would invite reading it as how far it should
 * have got, and a review waiting for or skipped on its checks has run nothing at all.
 */
const WHOLE_STATE: Partial<Record<ReviewStatus, string>> = {
  cancelled: 'cancelled', superseded: 'superseded', refused: 'refused', observed: 'observed',
  waiting_for_checks: 'waiting for checks', skipped: 'skipped — checks failed',
};

interface Segment {
  cls: string;
  word: string;
}

const SEGMENT_WORD: Record<string, string> = {
  done: 'done', active: 'in progress', failed: 'failed', '': 'not started',
};

/** Done before the stage, `at` on it, nothing after: a running review is active there, a failed one stalled. */
function progressSegments(stage: number, at: 'active' | 'failed'): Segment[] {
  return ROW_STEPS.map((_, i) => {
    const cls = i < stage ? 'done' : i === stage ? at : '';
    return { cls, word: SEGMENT_WORD[cls] };
  });
}

/** Each step's class and word, and the sentence the whole strip says, for a status at a stage. */
function pipelineOf(status: ReviewStatus, stage: number): { segments: Segment[]; summary: string } {
  if (status === 'reviewing') {
    return { segments: progressSegments(stage, 'active'), summary: `in progress — ${stageLabel(stage)}` };
  }
  if (status === 'failed') {
    return { segments: progressSegments(stage, 'failed'), summary: `stalled at ${stageLabel(stage)}` };
  }
  const whole = WHOLE_STATE[status];
  if (whole) {
    return { segments: ROW_STEPS.map(() => ({ cls: `st-${status}`, word: whole })), summary: whole };
  }
  return { segments: ROW_STEPS.map(() => ({ cls: 'done', word: 'done' })), summary: 'done' };
}

/**
 * The row's pipeline: segments only, no trailing word. Each segment names its step and state on hover, and the
 * strip's accessible name says the state the word used to say ("Pipeline: skipped — checks failed"). No segment is a
 * tab stop: the row is, and its accessible name includes the strip's.
 */
export function miniPipeline(status: ReviewStatus, stage: number) {
  const { segments, summary } = pipelineOf(status, stage);
  return (
    <div className="mini" role="group" aria-label={`Pipeline: ${summary}`}>
      {segments.map((s, i) => {
        const label = `${ROW_STEPS[i]}: ${s.word}`;
        return (
          <Tooltip key={ROW_STEPS[i]} label={label} className="mini-step">
            <span className={`seg ${s.cls}`} role="img" aria-label={label}></span>
          </Tooltip>
        );
      })}
    </div>
  );
}

/** "7 open: 4 new, 3 carried": what the bare number is made of, said on hover and to a screen reader. */
export function findingsBreakdown(findings: number, carriedOver: number): string {
  if (findings === 0) return 'No open findings';
  const carried = Math.max(0, Math.min(carriedOver, findings));
  return `${findings} open: ${findings - carried} new, ${carried} carried`;
}

/** Statuses whose count would describe a diff no model finished reading. */
const NO_COUNT = new Set<ReviewStatus>(['reviewing', 'waiting_for_checks', 'failed', 'cancelled', 'refused', 'skipped']);

/**
 * The open-findings count as a number only; its split into new and carried-over findings is the tooltip. A bare
 * total going 1 → 2 reads as "my fix made things worse" when it is as often one new beside one never fixed.
 *
 * <p>A dash while running (a "0 so far" reads as a result), when no model saw the diff (failed, cancelled, refused,
 * skipped) and when the run produced nothing (degraded): a `0` there is how a degraded run passed for a clean one.
 */
export function findCell(status: ReviewStatus, findings: number, carriedOver = 0, degraded = false) {
  if (NO_COUNT.has(status) || degraded) return <span className="time">—</span>;
  const breakdown = findingsBreakdown(findings, carriedOver);
  return (
    <Tooltip label={breakdown}>
      <span className={`findcount ${findings === 0 ? 'zero' : 'some'} tnum`}>
        {findings}
        <span className="sr-only">{` — ${breakdown}`}</span>
      </span>
    </Tooltip>
  );
}
