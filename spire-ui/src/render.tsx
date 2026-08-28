import { useEffect, useRef, useState } from 'react';
import { Archive, Bot, CheckCircle2, Cpu, GitMerge, GitPullRequest, GitPullRequestClosed, MessageCircle, MessageSquare } from 'lucide-react';
import { FindingConversation } from './components/FindingConversation';
import { MessageText } from './components/MessageText';
import { RiOpenaiFill } from 'react-icons/ri';
import { SiClaude, SiGooglegemini } from 'react-icons/si';
import type {
  Finding,
  PrState,
  ReconciliationItem,
  ReviewDetail,
  ReviewEvent,
  ReviewStatus,
} from './api';
import { formatEventTime } from './format';

export { formatEventTime };

export const STAGES = ['Received', 'Diff', 'Context', 'Review', 'Comments', 'Done'];

/** Derived, not a literal 2, so reordering the pipeline above moves this with it. */
export const CONTEXT_STAGE = STAGES.indexOf('Context');

/** Label for a stage index, clamped — the backend can report stage 6 ("past Done"). */
export function stageLabel(stage: number): string {
  return STAGES[Math.min(stage, STAGES.length - 1)];
}

/**
 * Returns the URL only when it parses as http(s); undefined otherwise. Server-provided
 * URLs (e.g. `htmlUrl`) must pass through this before landing in an <a href> — React
 * does not block `javascript:`/`data:` schemes.
 */
export function safeHttpUrl(u: string | undefined): string | undefined {
  if (!u) return undefined;
  try {
    const parsed = new URL(u);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? u : undefined;
  } catch {
    return undefined;
  }
}

/** Anything carrying a stored SCM type + a PR URL — both ReviewSummary and ReviewDetail. */
export type ProviderSource = { providerType?: string; htmlUrl?: string };

/**
 * Canonical provider slug ('github' | 'gitlab' | 'bitbucket' | '—'). Prefers the
 * stored SCM type (correct for self-hosted GitLab/Bitbucket whose host contains
 * none of the vendor names); falls back to sniffing the URL host only for legacy
 * rows written before the type was persisted.
 */
export function providerLabel(r: ProviderSource | undefined): string {
  const t = (r?.providerType ?? '').toLowerCase();
  if (t === 'github') return 'github';
  if (t === 'gitlab') return 'gitlab';
  if (t === 'bitbucket-cloud' || t === 'bitbucket-dc') return 'bitbucket';
  const u = (r?.htmlUrl ?? '').toLowerCase();
  if (u.includes('github')) return 'github';
  if (u.includes('gitlab')) return 'gitlab';
  if (u.includes('bitbucket')) return 'bitbucket';
  return '—';
}

/** A small provider badge from the stored type (null when unknown). */
export function providerBadge(r: ProviderSource | undefined) {
  const p = providerLabel(r);
  if (p === '—') return null;
  return <span className={`prov-badge prov-${p}`}>{p}</span>;
}

/**
 * The pull/merge request's own state (open/merged/closed) — a badge distinct from the
 * review-outcome/status badge (`outcomeBadge`/`statusCell`), shown beside it in the
 * reviews-list row and the detail header.
 */
export function prStateBadge(prState: PrState) {
  const [cls, Icon, label] =
    prState === 'MERGED' ? ['pr-merged', GitMerge, 'Merged'] :
    prState === 'CLOSED' ? ['pr-closed', GitPullRequestClosed, 'Closed'] :
    ['pr-open', GitPullRequest, 'Open'];
  return (
    <span className={`pr-state ${cls}`} title={`Pull request ${label.toLowerCase()}`}>
      <Icon size={13} aria-hidden="true" />
      {label}
    </span>
  );
}

/**
 * The archival marker, or nothing at all for a live review.
 *
 * <p>Shared by the list row and the detail header on purpose: archived rows sit inline in the same
 * table as live work, and the detail page hides every action from a viewer — so in both places the
 * marker is the only thing saying that a frozen review is not simply a live one that went quiet.
 */
export function archivedBadge(archivedAt: string | null | undefined) {
  if (!archivedAt) return null;
  return (
    <span className="archived-tag" title={`Archived ${new Date(archivedAt).toLocaleString()}`}>
      <Archive size={11} aria-hidden="true" />
      Archived
    </span>
  );
}

/** The LLM vendor behind a model — from the catalogued provider type, else inferred from the name. */
function llmProvider(llmType: string, model: string): 'openai' | 'anthropic' | 'gemini' | '' {
  if (llmType === 'openai' || llmType === 'anthropic' || llmType === 'gemini') return llmType;
  const m = model.toLowerCase();
  if (m.startsWith('gemini')) return 'gemini';
  if (m.startsWith('claude')) return 'anthropic';
  if (m.startsWith('gpt') || m.startsWith('o1') || m.startsWith('o3') || m.startsWith('chatgpt') || m.startsWith('openai'))
    return 'openai';
  return '';
}

/** The real vendor logo (react-icons) for the model that produced a review; tooltip = model name. */
export function llmIcon(model: string, llmType: string) {
  if (!model) return <span className="llm-icon-empty">—</span>;
  const p = llmProvider(llmType, model);
  const Logo = p === 'openai' ? RiOpenaiFill : p === 'anthropic' ? SiClaude : p === 'gemini' ? SiGooglegemini : null;
  if (!Logo) {
    return (
      <span className="llm-icon llm-unknown" title={model} aria-label={`Model: ${model}`}>
        <Cpu size={15} />
      </span>
    );
  }
  return (
    <span className={`llm-icon llm-${p}`} title={model} aria-label={`Model: ${model}`}>
      <Logo size={15} />
    </span>
  );
}

/** Full hashes (GitHub is 40 chars) get truncated for display; the copy button carries the full value. */
export function shortSha(sha: string): string {
  return sha.length > 12 ? sha.slice(0, 10) : sha;
}

const COPY_ICON = (
  <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" stroke="currentColor" strokeWidth="1.3" />
    <path d="M3.5 10.5H3a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1h6.5a1 1 0 0 1 1 1v.5" stroke="currentColor" strokeWidth="1.3" />
  </svg>
);
const CHECK_ICON = (
  <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
    <path d="M3 8.5l3.2 3.2L13 5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

/** Copy-to-clipboard icon button; stops row-click propagation and flashes a check. */
export function CopyButton({ text, title }: { text: string; title: string }) {
  const [copied, setCopied] = useState(false);
  const resetTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(
    () => () => {
      if (resetTimer.current) clearTimeout(resetTimer.current);
    },
    [],
  );
  return (
    <button
      type="button"
      className={`copybtn ${copied ? 'copied' : ''}`}
      title={title}
      aria-label={title}
      onClick={(e) => {
        e.stopPropagation();
        void navigator.clipboard?.writeText(text);
        setCopied(true);
        if (resetTimer.current) clearTimeout(resetTimer.current);
        resetTimer.current = setTimeout(() => setCopied(false), 1200);
      }}
    >
      {copied ? CHECK_ICON : COPY_ICON}
    </button>
  );
}

/**
 * Generic "value that may be long" widget: shows {@link display} (or the text),
 * truncated with an ellipsis when it doesn't fit, and a copy button carrying the
 * FULL text. The full value is always in the tooltip. Reused by the reviews table
 * and the detail metadata so truncate-plus-copy is written once.
 */
export function CopyableValue({
  text,
  display,
  mono,
  copyTitle,
}: {
  text: string;
  display?: string;
  mono?: boolean;
  copyTitle?: string;
}) {
  if (!text) return <span className="copyval-empty">—</span>;
  return (
    <span className="copyval">
      <span className={`copyval-text ${mono ? 'mono' : ''}`} title={text}>
        {display ?? text}
      </span>
      <CopyButton text={text} title={copyTitle ?? 'Copy'} />
    </span>
  );
}

/** "Open in GitHub / GitLab / Bitbucket" — matches the real provider (was hardcoded). */
export function openInLabel(r: ProviderSource | undefined): string {
  const p = providerLabel(r);
  const name = p === 'github' ? 'GitHub' : p === 'gitlab' ? 'GitLab' : p === 'bitbucket' ? 'Bitbucket' : 'provider';
  return `Open in ${name}`;
}

export const STATUS_LABEL: Record<ReviewStatus, string> = {
  reviewing: 'Reviewing',
  completed: 'Completed',
  failed: 'Attention',
  cancelled: 'Cancelled',
  superseded: 'Superseded',
  refused: 'Refused',
  observed: 'Observed',
};

export function ago(updatedAt: string): string {
  const s = (Date.now() - Date.parse(updatedAt)) / 1000;
  if (s < 60) return Math.round(s) + 's ago';
  if (s < 3600) return Math.round(s / 60) + 'm ago';
  if (s < 86400) return Math.round(s / 3600) + 'h ago';
  return Math.round(s / 86400) + 'd ago';
}

export function pill(status: ReviewStatus) {
  return <span className={`pill ${status}`}>{STATUS_LABEL[status]}</span>;
}

/**
 * Outcome-aware badge: a finished review is only "Passed" when it's clean. Findings with a
 * blocker read as "Changes requested"; lower-severity-only findings read as "Suggestions".
 * Non-terminal states (reviewing/failed/…) fall back to the plain status pill.
 */
export function outcomeBadge(status: ReviewStatus, findings: number, blockerCount: number,
                             degraded = false) {
  if (status === 'completed') {
    // A run that produced nothing has no outcome to report. Zero findings is also what a clean
    // pass writes, so without this the most prominent cell on the row asserts "Passed" about a
    // review that never happened — the same false-success rendering the `refused` status hit.
    if (degraded) return <span className="pill pill--warn">No result</span>;
    const [cls, label] =
      findings === 0
        ? ['passed', 'Passed']
        : blockerCount > 0
          ? ['changes', 'Changes']
          : ['suggestions', 'Suggestions'];
    return <span className={`pill ${cls}`}>{label}</span>;
  }
  return pill(status);
}

/**
 * Subtle "responding…" indicator shown while the bot is composing a follow-up reply
 * (`answering: true`). `answering` is best-effort and can rarely stay true if a
 * follow-up dies terminally, so this is a static pill — not an unbounded spinner
 * that would read as "stuck forever".
 */
export function respondingPill() {
  return (
    <span className="responding" title="The bot is composing a reply">
      <MessageCircle size={11} />
      responding…
    </span>
  );
}

/**
 * The status cell: the outcome badge plus the "responding…" indicator when the bot
 * is mid-reply. Shared by the reviews-list row and the detail header so both stay
 * in sync with the same gating logic.
 */
export function statusCell(r: { status: ReviewStatus; findings: number; blockerCount: number;
                                answering?: boolean; degraded?: boolean }) {
  return (
    <>
      {outcomeBadge(r.status, r.findings, r.blockerCount, r.degraded)}
      {r.answering && respondingPill()}
    </>
  );
}

// In the list, ReviewSummary carries only `status` + `stage` (no per-segment
// array), so segment states are derived exactly as the mockup's sample data
// would have produced them.
export function miniPipeline(status: ReviewStatus, stage: number) {
  if (status === 'reviewing') {
    const segs = Array.from({ length: 5 }, (_, i) => {
      const cls = i < stage ? 'done' : i === stage ? 'active' : '';
      return <span key={i} className={`seg ${cls}`}></span>;
    });
    return (
      <div className="mini">
        {segs}
        <span className="lbl">{stageLabel(stage)}</span>
      </div>
    );
  }
  if (status === 'failed') {
    const segs = Array.from({ length: 5 }, (_, i) => {
      const cls = i < stage ? 'done' : i === stage ? 'failed' : '';
      return <span key={i} className={`seg ${cls}`}></span>;
    });
    return (
      <div className="mini">
        {segs}
        <span className="lbl" style={{ color: 'var(--crit)' }}>
          stalled
        </span>
      </div>
    );
  }
  if (status === 'cancelled' || status === 'superseded') {
    return (
      <div className="mini">
        <span className="lbl">{status === 'cancelled' ? 'cancelled' : 'superseded'}</span>
      </div>
    );
  }
  // Before this branch existed, a refused review fell through to the terminal "done" below and
  // rendered as five green segments — a review the deployment declined to spend on, shown as a
  // successful one. Stage segments are omitted entirely: a cap refuses at the diff or the review
  // stage, so drawing progress would only invite reading how far it got as how far it should have.
  if (status === 'refused') {
    return (
      <div className="mini">
        <span className="lbl">refused</span>
      </div>
    );
  }
  if (status === 'observed') {
    return (
      <div className="mini">
        <span className="lbl">observed</span>
      </div>
    );
  }
  return (
    <div className="mini">
      {Array.from({ length: 5 }, (_, i) => (
        <span key={i} className="seg done"></span>
      ))}
      <span className="lbl" style={{ color: 'var(--good)' }}>
        done
      </span>
    </div>
  );
}

/**
 * The open-findings count, split so its movement explains itself.
 *
 * <p>A bare total going 1 → 2 between rounds reads as "my fix made things worse", when it is just
 * as often one new finding beside one that was already open and never fixed. The breakdown is only
 * rendered when something actually is carried over, so an ordinary first review still shows a plain
 * number rather than paying for a distinction that does not apply to it.
 */
export function findCell(status: ReviewStatus, findings: number, carriedOver = 0, degraded = false) {
  // While a review is still running the running tally is noise (and "0 so far"
  // reads as a result). Show a neutral placeholder until the review completes.
  if (status === 'reviewing') return <span className="time">—</span>;
  // `refused` belongs here for a stronger reason than the other two: a cap declined to run the
  // review at all, so a `0` would be a findings count for a diff no model ever saw.
  if (status === 'failed' || status === 'cancelled' || status === 'refused')
    return <span className="time">—</span>;
  // A degraded run reports zero findings, and a plain `0` is exactly how it used to pass for a clean
  // review here. The placeholder rather than a second pill: the outcome badge already carries the
  // state, and a count for a review that produced nothing is not a count — which is why `failed`,
  // `cancelled` and `refused` above render the same way.
  if (degraded) return <span className="time">—</span>;
  if (findings === 0) return <span className="findcount zero tnum">0</span>;
  if (carriedOver <= 0) return <span className="findcount some tnum">{findings}</span>;
  return (
    <span
      className="findcount some tnum"
      title={`${findings - carriedOver} raised by this run · ${carriedOver} still open from an earlier run`}
    >
      {findings}
      <small>{carriedOver} carried</small>
    </span>
  );
}

export function stepper(r: ReviewDetail) {
  return (
    <div className="stepper">
      {STAGES.map((name, i) => {
        const st = r.stages[i] || 'pending';
        const cls = st === 'done' ? 'done' : st === 'active' ? 'active' : st === 'failed' ? 'failed' : 'pending';
        const node = st === 'done' ? '✓' : st === 'failed' ? '✕' : st === 'active' ? '' : i + 1;
        const barDone = r.stages[i] === 'done' ? 'done' : '';
        const t = r.timings[i] && r.timings[i].trim() ? r.timings[i] : cls === 'pending' ? '—' : '';
        return (
          <div key={i} className={`step ${cls}`}>
            <div className={`bar ${barDone}`}></div>
            <div className="node">{node}</div>
            <div className="name">{name}</div>
            <div className="t">{t}</div>
          </div>
        );
      })}
    </div>
  );
}

/** CSS-safe slug for a status string ('still open' -> 'still-open'). */
function statusSlug(status: string): string {
  return status.replace(/\s+/g, '-');
}

const RECON_CLOSED_STATUSES = new Set(['resolved', 'acknowledged', 'superseded']);

/**
 * One row of the unified findings list — either this round's new finding or a reconciliation
 * verdict against a prior round's finding, normalized so both render with the same markup.
 */
interface FindingRow {
  key: string;
  sev: Finding['sev'];
  loc: string;
  msg: string;
  note?: string;
  status: string; // 'new' | the reconciliation verdict ('still open' | 'unchanged' | 'resolved' | …)
  closed: boolean;
  threadRef?: string;
  resolvedThread?: boolean;
  // 'conversation' when a human filed this finding with /finding; absent for a review-derived one.
  // Carried by BOTH row kinds: a filed finding becomes a reconciliation verdict the round after it
  // is filed, so a badge only the fresh-finding row could show would last exactly one round.
  origin?: 'conversation';
}

/**
 * Every row the findings card renders — this run's findings PLUS the reconciliation entries. Shared
 * with {@link generalDiscussionCard} so the two cards are mutually exclusive BY CONSTRUCTION: the
 * backend's `threadKind` is derived only from this run's findings_json locs, so after a re-review
 * whose findings land on different lines, a finding's conversation classifies as a bare mention while
 * the findings card still nests it by threadRef — and the same thread rendered in both places.
 */
function findingRowsOf(r: ReviewDetail): FindingRow[] {
  const findingsList = r.findingsList ?? [];
  const ownedThreads = new Set(
    findingsList.filter((f): f is Finding & { threadRef: string } => !!f.threadRef).map((f) => f.threadRef),
  );
  return [...newFindingRows(findingsList), ...reconciliationRows(r.reconciliation ?? [], ownedThreads)];
}

/** The threads the findings card owns — what general discussion must leave alone. */
function findingThreadRefs(r: ReviewDetail): Set<string> {
  return new Set(
    findingRowsOf(r).map((row) => row.threadRef).filter((ref): ref is string => !!ref),
  );
}

function newFindingRows(findingsList: Finding[]): FindingRow[] {
  return findingsList.map((f, i) => ({
    key: `new-${i}`,
    sev: f.sev,
    loc: f.loc,
    msg: f.msg,
    status: 'new',
    closed: false,
    threadRef: f.threadRef,
    origin: f.origin,
  }));
}

/**
 * Reconciliation verdicts as rows, excluding any whose thread a new finding already owns. The
 * two sets are disjoint by construction, but if the same thread ever appears in both, the
 * findingsList copy (already rendered above) wins rather than showing the thread twice.
 */
function reconciliationRows(reconciliation: ReconciliationItem[], ownedThreads: Set<string>): FindingRow[] {
  return reconciliation
    .filter((i) => !(i.threadRef && ownedThreads.has(i.threadRef)))
    .map((i, idx) => ({
      key: `recon-${i.threadRef ?? idx}`,
      sev: i.sev,
      loc: i.loc,
      msg: i.msg,
      note: i.note,
      status: i.status,
      closed: RECON_CLOSED_STATUSES.has(i.status),
      threadRef: i.threadRef,
      resolvedThread: i.resolvedThread,
      origin: i.origin,
    }));
}

/** One findings-list row — a severity badge, location, message, status badge (with a check icon
 *  once closed) and the existing per-finding conversation toggle. */
function findingRow(r: ReviewDetail, row: FindingRow) {
  const turns = row.threadRef
    ? r.events.filter(
        (e: ReviewEvent) => e.threadRef === row.threadRef && (e.type === 'AuthorReplied' || e.type === 'FollowUpGenerated'),
      )
    : [];
  // A reconciliation verdict posts a reply into the thread ON THE SCM but stores no conversation turn
  // (only a ThreadReplied/ThreadResolved marker), so without this the bot's "still open after <sha>"
  // reply was unreachable from the UI — the panel only appeared once a human had started a discussion.
  const hasScmReply = !!row.threadRef && r.events.some(
    (e: ReviewEvent) => e.threadRef === row.threadRef && (e.type === 'ThreadReplied' || e.type === 'ThreadResolved'),
  );
  const resolvedThread =
    row.resolvedThread ??
    (row.threadRef ? r.reconciliation?.find((item) => item.threadRef === row.threadRef)?.resolvedThread : undefined);
  return (
    <div
      key={row.key}
      className={`finding recon-item recon-${statusSlug(row.status)} ${row.sev}${row.closed ? ' finding-closed' : ''}`}
    >
      <div className="stripe"></div>
      <div className="fbody">
        <div className="frow">
          <span className="sev">{row.sev}</span>
          {row.origin === 'conversation' && (
            <span className="pill provenance" title="Filed by a person with /finding in a review thread">
              <MessageSquare size={11} aria-hidden="true" /> from discussion
            </span>
          )}
          <span className="loc">{row.loc}</span>
          <span className="recon-verdict">
            {row.closed && <CheckCircle2 size={13} />}
            {row.status}
          </span>
        </div>
        <div className="msg">{row.msg}</div>
        {row.note ? (
          <div className="recon-note">
            <MessageText>{row.note}</MessageText>
          </div>
        ) : null}
        {row.threadRef && (turns.length > 0 || hasScmReply) && (
          <FindingConversation
            workspace={r.workspace}
            slug={r.slug}
            pr={r.pr}
            threadRef={row.threadRef}
            previewTurns={turns}
            previewBody={
              turns.length > 0 ? (
                conversationExchangesBody(turns)
              ) : (
                <div className="convo-note">Couldn’t load this thread from the provider.</div>
              )
            }
            resolved={resolvedThread}
          />
        )}
      </div>
    </div>
  );
}

/** How a status that has no findings to list explains itself instead. */
interface StatusExplanation {
  heading: string;
  /** Severity class behind the card's stripe: an operator has something to do, or hasn't. */
  tone: 'warning' | 'nit';
}

/**
 * The statuses whose card says what happened rather than listing findings — a lookup, so a status
 * missing from it is the *absence* of an explanation card rather than a silent fall-through into
 * the branches below.
 *
 * <p>`refused` is here because it is the case that fall-through got wrong: a cap declined to spend
 * on the diff, and with no findings and no reconciliation the card told the operator it was clean.
 * Its own heading, because nothing stalled and nothing stopped — the review never ran. This is also
 * the only place in the UI that renders `r.note`, which for a refusal is the actionable half (raise
 * the cap, or split the pull request).
 */
const STATUS_EXPLANATIONS: Partial<Record<ReviewStatus, StatusExplanation>> = {
  failed: { heading: 'Why it stalled', tone: 'warning' },
  cancelled: { heading: 'Why it stopped', tone: 'nit' },
  refused: { heading: 'Why it was refused', tone: 'warning' },
};

/** Not a status, so it cannot live in the record above — see {@link findingsCard}. */
const DEGRADED_EXPLANATION: StatusExplanation = { heading: 'Why there is no result', tone: 'warning' };

/** Shown when a status that owes an explanation carries no note — an empty card reads as
 *  "nothing to see here", which is the reassurance these branches exist to withhold. */
const NO_NOTE_RECORDED = 'No explanation was recorded.';

/**
 * The unified findings list: this round's new findings plus the prior round's reconciliation
 * verdicts, as one list. Open items (new / still open / unchanged) render first; closed verdicts
 * (resolved / acknowledged / superseded) are grouped in a collapsed "Resolved (N)" section at the
 * bottom so a multi-round review reads as one place to look, not two near-empty cards.
 */
export function findingsCard(r: ReviewDetail) {
  // Keyed by status, so it can only ever describe a condition that IS one. `degraded` is a separate
  // dimension on purpose (like `truncated` and `archivedAt`), which means a run that produced
  // nothing fell straight past this to the empty-findings branch and told the operator the diff was
  // reviewed and clean — the identical failure `refused` had, for the identical reason. The note is
  // rendered ONLY inside this branch, so it was also written, stored, sent over the wire and shown
  // nowhere.
  const explanation = r.degraded && r.status === 'completed'
    ? DEGRADED_EXPLANATION
    : STATUS_EXPLANATIONS[r.status];
  if (explanation) {
    return (
      <div className="card">
        <div className="head">
          <span className="k">//</span>
          <h3>{explanation.heading}</h3>
        </div>
        <div className="body">
          <div className={`finding ${explanation.tone}`}>
            <div className="stripe"></div>
            <div className="fbody">
              <div className="msg" style={{ marginTop: 0 }}>{r.note?.trim() ? r.note : NO_NOTE_RECORDED}</div>
            </div>
          </div>
          {r.errorDetail && (
            <details className="error-detail" open>
              <summary>Error detail</summary>
              <pre className="mono">{r.errorDetail}</pre>
            </details>
          )}
        </div>
      </div>
    );
  }

  const reconciliation = r.reconciliation ?? [];
  if (!r.findingsList.length && !reconciliation.length && r.status === 'reviewing') {
    return (
      <div className="card">
        <div className="head">
          <span className="k">//</span>
          <h3>Findings</h3>
        </div>
        <div className="body">
          <div className="clean">
            <span className="em mono">Analyzing the diff…</span>
          </div>
        </div>
      </div>
    );
  }
  if (!r.findingsList.length && !reconciliation.length) {
    return (
      <div className="card">
        <div className="head">
          <span className="k">//</span>
          <h3>Findings</h3>
          <span className="badge">0</span>
        </div>
        <div className="body">
          <div className="clean">
            <span className="em mono">✓ clean</span>No issues found in this diff.
          </div>
        </div>
      </div>
    );
  }

  const rows = findingRowsOf(r);
  const openRows = rows.filter((row) => !row.closed);
  const closedRows = rows.filter((row) => row.closed);

  const more =
    r.findings > r.findingsList.length ? (
      <div
        style={{
          textAlign: 'center',
          marginTop: 12,
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
          color: 'var(--text-3)',
        }}
      >
        + {r.findings - r.findingsList.length} more {r.status === 'reviewing' ? '· still generating' : ''}
      </div>
    ) : null;

  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Findings</h3>
        <span className="badge">
          {openRows.length} open · {closedRows.length} closed
        </span>
      </div>
      <div className="body">
        {openRows.map((row) => findingRow(r, row))}
        {more}
        {closedRows.length > 0 && (
          <details className="resolved-group">
            <summary>Resolved ({closedRows.length})</summary>
            <div className="resolved-group-body">{closedRows.map((row) => findingRow(r, row))}</div>
          </details>
        )}
      </div>
    </div>
  );
}

export function metaCard(r: ReviewDetail) {
  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>Metadata</h3>
      </div>
      <div className="body">
        <dl className="kv kv-meta">
          <dt>Review ID</dt>
          <dd>
            <CopyableValue text={r.id} mono copyTitle="Copy review ID" />
          </dd>
          <dt>Provider</dt>
          <dd>{providerBadge(r) ?? providerLabel(r)}</dd>
          <dt>Target</dt>
          <dd>{r.base}</dd>
          <dt>Head</dt>
          <dd>
            <CopyableValue text={r.sha} mono copyTitle="Copy commit hash" />
          </dd>
          <dt>Attempt</dt>
          <dd>{r.attempt}</dd>
        </dl>
      </div>
    </div>
  );
}

/** One message in the conversation, reshaped from a timeline event. */
interface ConversationTurn {
  who: string; // the author handle for a human reply; '' for the bot
  text: string; // the message body (author handle stripped)
  ts: string; // absolute ISO-8601 instant
  at: string; // friendly delta from review start
  isBot: boolean;
}

/** A question and the bot answer it drew — either side may be absent (a trailing question, or an
 * orphan answer whose question predates conversation persistence). */
interface ConversationExchange {
  question?: ConversationTurn;
  answer?: ConversationTurn;
}

/** Parse a conversation event into a turn. `AuthorReplied` details read "@handle: message". */
function toConversationTurn(e: ReviewEvent): ConversationTurn {
  if (e.type === 'FollowUpGenerated') {
    return { who: '', text: e.det, ts: e.ts, at: e.at, isBot: true };
  }
  const sep = e.det.indexOf(': ');
  const who = sep > 0 ? e.det.slice(0, sep) : '';
  const text = sep > 0 ? e.det.slice(sep + 2) : e.det;
  return { who, text, ts: e.ts, at: e.at, isBot: false };
}

/** Group ordered turns into question→answer exchanges: a human reply opens an exchange, the next
 * bot answer closes it. A bot answer with no open question stands alone (its question predates
 * persistence). */
function toConversationExchanges(events: ReviewEvent[]): ConversationExchange[] {
  const exchanges: ConversationExchange[] = [];
  let open: ConversationExchange | null = null;
  for (const e of events) {
    const turn = toConversationTurn(e);
    if (!turn.isBot) {
      open = { question: turn };
      exchanges.push(open);
    } else if (open && !open.answer) {
      open.answer = turn;
      open = null;
    } else {
      exchanges.push({ answer: turn });
    }
  }
  return exchanges;
}

/** One message row; `nested` indents + connects a bot answer under its question. */
function conversationTurnRow(turn: ConversationTurn, nested: boolean, key: string) {
  return (
    <div key={key} className={`convo-turn ${turn.isBot ? 'bot' : 'reply'}${nested ? ' nested' : ''}`}>
      <span className="convo-glyph" aria-hidden="true">
        {turn.isBot ? <Bot size={13} /> : '↩'}
      </span>
      <div className="convo-body">
        <div className="convo-who">{turn.isBot ? 'Code Spire' : turn.who}</div>
        <div className="convo-det">
          <MessageText>{turn.text}</MessageText>
        </div>
        <div className="convo-at">
          {formatEventTime(turn.ts)}
          <span className="convo-at-rel">{turn.at}</span>
        </div>
      </div>
    </div>
  );
}

/** The `<div className="convo">` body: ordered turns grouped into question→answer exchanges.
 *  Reused by the per-finding nested panel (findingsCard) and generalDiscussionCard. */
export function conversationExchangesBody(turns: ReviewEvent[]) {
  const exchanges = toConversationExchanges(turns);
  return (
    <div className="convo">
      {exchanges.map((ex: ConversationExchange, i: number) => (
        <div key={i} className="convo-exchange">
          {ex.question && conversationTurnRow(ex.question, false, 'q')}
          {ex.answer && conversationTurnRow(ex.answer, !!ex.question, 'a')}
        </div>
      ))}
    </div>
  );
}

/** Conversations NOT tied to a finding — summary-comment replies, @-mentions, and orphan bot
 *  answers (threadKind !== 'finding', including undefined). Hidden when empty.
 *
 *  One block per SCM thread, shaped like a finding: the OPENING message is always visible for
 *  context, and the remaining turns sit behind a "N replies" toggle that re-fetches the thread's FULL
 *  text from the SCM ({@link FindingConversation}) — the stored event detail is only a ≤160-char
 *  preview (ADR-011 keeps conversation text on the SCM), so rendering events alone left every reply
 *  truncated. A turn with no threadRef can't be re-fetched, so it falls back to the preview body. */
export function generalDiscussionCard(r: ReviewDetail) {
  const nestedUnderAFinding = findingThreadRefs(r);
  const turns = r.events.filter(
    (e: ReviewEvent) =>
      (e.type === 'AuthorReplied' || e.type === 'FollowUpGenerated') &&
      e.threadKind !== 'finding' &&
      !(e.threadRef && nestedUnderAFinding.has(e.threadRef)),
  );
  if (!turns.length) return null;
  const byThread = new Map<string, ReviewEvent[]>();
  const unthreaded: ReviewEvent[] = [];
  for (const turn of turns) {
    if (!turn.threadRef) {
      unthreaded.push(turn);
      continue;
    }
    const group = byThread.get(turn.threadRef) ?? [];
    group.push(turn);
    byThread.set(turn.threadRef, group);
  }
  return (
    <div className="card">
      <div className="head">
        <span className="k">//</span>
        <h3>General discussion</h3>
        <span className="badge">{turns.length}</span>
      </div>
      <div className="body">
        {[...byThread.entries()].map(([threadRef, group]) => {
          const [opening, ...replies] = group;
          // A thread the bot didn't start has no finding to nest under, so its location is the only
          // thing distinguishing "someone commented on line 9" from a general PR discussion.
          const loc = group.find((t) => t.loc)?.loc;
          return (
            <div key={threadRef} className="general-thread">
              {loc && <div className="general-thread-loc">{loc}</div>}
              {conversationExchangesBody([opening])}
              {replies.length > 0 && (
                <FindingConversation
                  workspace={r.workspace}
                  slug={r.slug}
                  pr={r.pr}
                  threadRef={threadRef}
                  previewTurns={replies}
                  previewBody={conversationExchangesBody(replies)}
                  rootShownAbove
                  openerPreview={opening.det}
                />
              )}
            </div>
          );
        })}
        {unthreaded.length > 0 && conversationExchangesBody(unthreaded)}
      </div>
    </div>
  );
}

