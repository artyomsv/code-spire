import type { ReactNode } from 'react';
import { GitMerge, GitPullRequest, GitPullRequestClosed, MessageCircle, type LucideIcon } from 'lucide-react';
import type { IconType } from 'react-icons';
import { SiBitbucket, SiGithub, SiGitlab } from 'react-icons/si';
import type { PrState } from '../api';
import Tooltip from './Tooltip';

/**
 * Icon-only marks for the reviews list and the review header (WIDGETS.md, "Compact review marks"). Each is a
 * focusable `role="img"` whose accessible name is its tooltip, so a keyboard user reads what a mouse user hovers.
 */
interface IconMarkProps {
  label: string;
  className: string;
  children: ReactNode;
}

function IconMark({ label, className, children }: IconMarkProps) {
  return (
    <Tooltip label={label}>
      <span className={`icon-mark ${className}`} role="img" aria-label={label} tabIndex={0}>
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
export function RespondingIcon() {
  return (
    <IconMark label={RESPONDING_LABEL} className="responding-icon">
      <MessageCircle size={14} aria-hidden="true" />
    </IconMark>
  );
}

const PR_STATES: Record<PrState, { cls: string; Icon: LucideIcon; word: string }> = {
  OPEN: { cls: 'pr-open', Icon: GitPullRequest, word: 'open' },
  MERGED: { cls: 'pr-merged', Icon: GitMerge, word: 'merged' },
  CLOSED: { cls: 'pr-closed', Icon: GitPullRequestClosed, word: 'closed' },
};

/** The pull request's own state as its icon: merged purple, open green, closed red. */
export function PrStateIcon({ prState }: { prState: PrState }) {
  const { cls, Icon, word } = PR_STATES[prState] ?? PR_STATES.OPEN;
  return (
    <IconMark label={`Pull request ${word}`} className={`pr-icon ${cls}`}>
      <Icon size={15} />
    </IconMark>
  );
}

const PROVIDERS: Record<string, { name: string; Logo: IconType }> = {
  github: { name: 'GitHub', Logo: SiGithub },
  gitlab: { name: 'GitLab', Logo: SiGitlab },
  bitbucket: { name: 'Bitbucket', Logo: SiBitbucket },
};

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
