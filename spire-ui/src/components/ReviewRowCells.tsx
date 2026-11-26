import type { ReviewStatus } from '../api';
import type { SeverityCounts } from '../severityCounts';
import { STAGES, stageLabel } from '../render';
import Tooltip from './Tooltip';
import { presentSeverities, severityBadges, severityWords, type SeverityCount } from './findingSeverities';

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

/** The sentence the strip says in place of its dropped word: "done", "stalled at Context", "skipped — checks failed". */
export function pipelineSummary(status: ReviewStatus, stage: number): string {
  return pipelineOf(status, stage).summary;
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

/** What the findings cell and its words read off a list row; a `ReviewSummary` is one. */
export interface FindingsRow {
  status: ReviewStatus;
  findings: number;
  carriedOverFindings?: number;
  degraded?: boolean;
  openBySeverity?: SeverityCounts | null;
}

/**
 * "7 open: 1 critical, 2 warnings, 4 suggestions; 4 new, 3 carried": what the badges (or, without per-severity
 * counts, the bare number) are made of, said on hover, to a screen reader and in the row's focus line.
 */
export function findingsBreakdown(row: FindingsRow): string {
  const { findings } = row;
  if (findings === 0) return 'No open findings';
  const carried = Math.max(0, Math.min(row.carriedOverFindings ?? 0, findings));
  const split = `${findings - carried} new, ${carried} carried`;
  const present = presentSeverities(findings, row.openBySeverity);
  return present ? `${findings} open: ${severityWords(present)}; ${split}` : `${findings} open: ${split}`;
}

/** Statuses whose count would describe a diff no model finished reading. */
const NO_COUNT = new Set<ReviewStatus>(['reviewing', 'waiting_for_checks', 'failed', 'cancelled', 'refused', 'skipped']);

/** The findings breakdown the count cell shows on hover, or null where the cell is a dash. */
export function findingsSummary(row: FindingsRow) {
  return NO_COUNT.has(row.status) || row.degraded ? null : findingsBreakdown(row);
}

/** One badge per severity, most severe first, in the findings card's colours; the words are the group's. */
function severityBadgeGroup(present: readonly SeverityCount[], breakdown: string) {
  return (
    <span className="sevbadges tnum">
      {severityBadges(present).map((b) => (
        <span key={b.key} className={`sevbadge sev-${b.key}`} aria-hidden="true">{b.text}</span>
      ))}
      <span className="sr-only">{breakdown}</span>
    </span>
  );
}

/**
 * The open findings as one small badge per severity (at most three, then "+N"), the full breakdown in words on hover
 * and in `sr-only` text. A bare total going 1 → 2 reads as "my fix made things worse" when it is as often one new
 * beside one never fixed, and a total alone cannot say whether the open findings are blockers or nits.
 *
 * <p>Zero, and a row without per-severity counts (an older server), stay the bare number. A dash while running (a
 * "0 so far" reads as a result), when no model saw the diff (failed, cancelled, refused, skipped) and when the run
 * produced nothing (degraded): a `0` there is how a degraded run passed for a clean one.
 */
export function findCell(row: FindingsRow) {
  const breakdown = findingsSummary(row);
  if (breakdown === null) return <span className="time">—</span>;
  const present = presentSeverities(row.findings, row.openBySeverity);
  return (
    <Tooltip label={breakdown}>
      {present ? severityBadgeGroup(present, breakdown) : (
        <span className={`findcount ${row.findings === 0 ? 'zero' : 'some'} tnum`}>
          {row.findings}
          <span className="sr-only">{` — ${breakdown}`}</span>
        </span>
      )}
    </Tooltip>
  );
}
