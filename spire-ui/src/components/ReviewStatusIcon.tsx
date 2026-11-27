import {
  Ban, CircleCheck, CircleDashed, CircleMinus, CircleX, Clock, Eye, Lightbulb, LoaderCircle, SkipForward,
  TriangleAlert, type LucideIcon,
} from 'lucide-react';
import type { ReviewStatus } from '../api';
import { outcomeOf, type OutcomeKey } from '../render';
import { IconMark, RespondingIcon } from './ReviewIcons';

interface OutcomeIcon {
  Icon: LucideIcon;
  /** The colour: `st-icon-<tone>` in index.css. */
  tone: string;
}

/**
 * Each outcome's icon and colour class (`st-icon-*` in index.css). Exhaustive on purpose: a new `ReviewStatus` widens
 * `OutcomeKey`, and this Record stops compiling until the status has an icon, so it can never fall into a success
 * mark the way `refused` once fell into five green segments.
 */
const OUTCOME_ICONS: Record<OutcomeKey, OutcomeIcon> = {
  passed: { Icon: CircleCheck, tone: 'good' },
  changes: { Icon: CircleX, tone: 'crit' },
  suggestions: { Icon: Lightbulb, tone: 'blue' },
  no_result: { Icon: CircleDashed, tone: 'warn' },
  reviewing: { Icon: LoaderCircle, tone: 'spin' },
  failed: { Icon: TriangleAlert, tone: 'crit' },
  waiting_for_checks: { Icon: Clock, tone: 'muted' },
  skipped: { Icon: SkipForward, tone: 'warn' },
  refused: { Icon: Ban, tone: 'crit' },
  cancelled: { Icon: CircleMinus, tone: 'muted' },
  superseded: { Icon: CircleMinus, tone: 'muted' },
  observed: { Icon: Eye, tone: 'muted' },
};

interface StatusRow {
  status: ReviewStatus;
  findings: number;
  blockerCount: number;
  answering?: boolean;
  degraded?: boolean;
}

/**
 * The reviews list's status: one coloured icon whose tooltip and accessible name are the full word the review header
 * shows as a badge ("Skipped: checks failed", not a shortened one). Not a tab stop: the row is (ReviewIcons).
 */
export function ReviewStatusIcon({ status, findings, blockerCount, degraded }: StatusRow) {
  const { key, label } = outcomeOf(status, findings, blockerCount, degraded);
  const { Icon, tone } = OUTCOME_ICONS[key];
  return (
    <IconMark label={label} className={`status-icon st-icon-${tone}`}>
      <Icon size={16} aria-hidden="true" />
    </IconMark>
  );
}

/** The list row's status cell: the status icon, and the responding icon while the bot is composing a reply. */
export function listStatusCell(r: StatusRow) {
  return (
    <>
      <ReviewStatusIcon {...r} />
      {r.answering && <RespondingIcon />}
    </>
  );
}
