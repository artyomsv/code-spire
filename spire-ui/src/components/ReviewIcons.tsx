import type { ReactNode } from 'react';
import { GitMerge, GitPullRequest, GitPullRequestClosed, MessageCircle, type LucideIcon } from 'lucide-react';
import type { IconType } from 'react-icons';
import { SiBitbucket, SiGithub, SiGitlab } from 'react-icons/si';
import type { PrState } from '../api';
import Tooltip from './Tooltip';

/**
 * Icon-only marks for the reviews list and the review header (WIDGETS.md, "Compact review marks"). Each is a
 * `role="img"` whose accessible name is its tooltip, so a screen reader reads what a mouse user hovers. In a list row
 * a mark is not a tab stop: the row is the keyboard stop, its accessible name carries every mark's word, and Enter
 * opens the detail where everything is shown in full. The detail header has no row to stand in for them, so it passes
 * `tabIndex={0}` and a keyboard user opens each tooltip by focusing the mark.
 */
interface MarkFocus {
  /** 0 makes the mark a tab stop (the detail header); absent, it is not one (a list row). */
  tabIndex?: 0;
}

interface IconMarkProps extends MarkFocus {
  label: string;
  className: string;
  children: ReactNode;
}

export function IconMark({ label, className, children, tabIndex }: IconMarkProps) {
  return (
    <Tooltip label={label}>
      <span className={`icon-mark ${className}`} role="img" aria-label={label} tabIndex={tabIndex}>
        {children}
      </span>
    </Tooltip>
  );
}

export const RESPONDING_LABEL = 'Responding to a reply…';

/**
 * The bot is composing a reply (`answering`). A soft pulse, not a spinner: `answering` is cleared by the reply, a
 * declined reply and a closed pull request, but a reply that dead-letters keeps it until the next review run.
 */
export function RespondingIcon({ tabIndex }: MarkFocus) {
  return (
    <IconMark label={RESPONDING_LABEL} className="responding-icon" tabIndex={tabIndex}>
      <MessageCircle size={14} aria-hidden="true" />
    </IconMark>
  );
}

const PR_STATES: Record<PrState, { cls: string; Icon: LucideIcon; word: string }> = {
  OPEN: { cls: 'pr-open', Icon: GitPullRequest, word: 'open' },
  MERGED: { cls: 'pr-merged', Icon: GitMerge, word: 'merged' },
  CLOSED: { cls: 'pr-closed', Icon: GitPullRequestClosed, word: 'closed' },
};

interface PrStateIconProps extends MarkFocus {
  prState: PrState;
}

/** "Pull request merged": what the state icon says on hover. */
export function prStateLabel(prState: PrState): string {
  return `Pull request ${(PR_STATES[prState] ?? PR_STATES.OPEN).word}`;
}

/** The pull request's own state as its icon: merged purple, open green, closed red. */
export function PrStateIcon({ prState, tabIndex }: PrStateIconProps) {
  const { cls, Icon } = PR_STATES[prState] ?? PR_STATES.OPEN;
  return (
    <IconMark label={prStateLabel(prState)} className={`pr-icon ${cls}`} tabIndex={tabIndex}>
      <Icon size={15} />
    </IconMark>
  );
}

const PROVIDERS: Record<string, { name: string; Logo: IconType }> = {
  github: { name: 'GitHub', Logo: SiGithub },
  gitlab: { name: 'GitLab', Logo: SiGitlab },
  bitbucket: { name: 'Bitbucket', Logo: SiBitbucket },
};

/** The forge's name for `providerLabel`'s slug ("GitHub"), or null for a forge with no logo. */
export function providerName(provider: string): string | null {
  return PROVIDERS[provider]?.name ?? null;
}

/** The forge's logo from `providerLabel`'s slug; an unknown forge keeps the list's plain dash. */
export function ProviderIcon({ provider }: { provider: string }) {
  const known = PROVIDERS[provider];
  if (!known) return <span className="prov-none">—</span>;
  const { name, Logo } = known;
  return (
    <IconMark label={name} className={`prov-icon prov-${provider}`}>
      <Logo size={14} />
    </IconMark>
  );
}
