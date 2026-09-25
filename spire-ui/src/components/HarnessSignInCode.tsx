import { useEffect, useRef, useState } from 'react';
import { Check, Clock, Copy, ExternalLink } from 'lucide-react';
import Tooltip from './Tooltip';

interface Props {
  verificationUri: string;
  userCode: string;
  /** "13m 30s", or null once the code has run out. Recomputed by the parent on every poll. */
  remaining: string | null;
}

/** What the last click achieved. A copy is reported only once the browser has confirmed it. */
type Outcome = 'copied' | 'copy-failed' | 'opened-copied' | 'opened-copy-failed' | null;

/**
 * What the operator does with a device code: open one page, type one code (M3.5 part F).
 *
 * <p>Two numbered steps rather than a link and a code in a column, because that is the order they are
 * used in, and each carries the one action it needs.
 *
 * <p>The open action starts the copy, then opens the page in the same click. The copy is STARTED first
 * because a browser refuses a clipboard write once focus has moved to the new tab; the page is opened
 * without waiting for it because a window opened after an await is no longer part of the click, and a
 * browser blocks it as a pop-up. So the result of the copy arrives after the page is open, and is
 * reported then — "copied" only when the browser said so, never assumed (review of PR #177).
 *
 * <p>The link is offered only when it is https. The worker already accepts only the vendor's own host,
 * so this is the second lock, placed where a click would follow it.
 */
export default function HarnessSignInCode({ verificationUri, userCode, remaining }: Props) {
  const [outcome, setOutcome] = useState<Outcome>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; clearTimeout(timer.current); }, []);
  const safeUri = isHttps(verificationUri) ? verificationUri : null;

  function report(next: Outcome) {
    if (!mounted.current) return;
    setOutcome(next);
    clearTimeout(timer.current);
    // A failure stays until the next click: the operator has to act on it. A success fades.
    if (next === 'copied' || next === 'opened-copied') timer.current = setTimeout(() => setOutcome(null), 2000);
  }

  function copyCode() {
    void copy(userCode).then(done => report(done ? 'copied' : 'copy-failed'));
  }

  function copyAndOpen() {
    if (!safeUri) return;
    const copying = copy(userCode);
    window.open(safeUri, '_blank', 'noopener,noreferrer');
    void copying.then(done => report(done ? 'opened-copied' : 'opened-copy-failed'));
  }

  const failed = outcome === 'copy-failed' || outcome === 'opened-copy-failed';
  return <div className="signin-code">
    <ol className="signin-steps">
      <li>
        <span className="signin-step-title">Open the sign-in page</span>
        <div className="reveal-value">
          {safeUri
            ? <a className="mono signin-link" href={safeUri} target="_blank" rel="noopener noreferrer">{safeUri}</a>
            : <span className="mono">{verificationUri}</span>}
          {safeUri && <Tooltip label="Copy the code, then open this page">
            <button type="button" className="icon-btn" aria-label="Copy the code and open the sign-in page"
              onClick={copyAndOpen}>
              {outcome === 'opened-copied' ? <Check size={16} /> : <ExternalLink size={16} />}
            </button>
          </Tooltip>}
        </div>
      </li>
      <li>
        <span className="signin-step-title">Sign in with the account the factory will use, then enter this code</span>
        <div className="reveal-value">
          <span className="mono signin-user-code" aria-label="One-time code">{userCode}</span>
          <Tooltip label="Copy the code">
            <button type="button" className="icon-btn" aria-label="Copy the code" onClick={copyCode}>
              {outcome === 'copied' ? <Check size={16} /> : <Copy size={16} />}
            </button>
          </Tooltip>
        </div>
      </li>
    </ol>
    <p className={`signin-expiry${failed ? ' signin-copy-failed' : ''}`} role="status">
      <Clock size={14} aria-hidden="true" />
      {statusLine(outcome, remaining)}
    </p>
  </div>;
}

/** True only when the browser confirmed the write. A missing API or a refusal is a failure, said as one. */
function copy(text: string): Promise<boolean> {
  const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
  if (!clipboard?.writeText) return Promise.resolve(false);
  try {
    return clipboard.writeText(text).then(() => true, () => false);
  } catch {
    return Promise.resolve(false);
  }
}

/** What the last click achieved, or else how long the code has left. */
function statusLine(outcome: Outcome, remaining: string | null): string {
  if (outcome === 'opened-copied') return 'Code copied. Paste it on the page that just opened.';
  if (outcome === 'opened-copy-failed') return 'The page opened, but the code could not be copied. Type it from here.';
  if (outcome === 'copied') return 'Code copied.';
  if (outcome === 'copy-failed') return 'The code could not be copied. Type it from here.';
  if (!remaining) return 'The code has expired.';
  return `The code expires in ${remaining}. This panel closes by itself once you approve.`;
}

function isHttps(uri: string): boolean {
  try { return new URL(uri).protocol === 'https:'; }
  catch { return false; }
}
