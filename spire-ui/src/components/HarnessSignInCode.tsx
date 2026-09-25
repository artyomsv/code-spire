import { useEffect, useRef, useState } from 'react';
import { Check, Clock, Copy, ExternalLink } from 'lucide-react';
import Tooltip from './Tooltip';

interface Props {
  verificationUri: string;
  userCode: string;
  /** "13m 30s", or null once the code has run out. Recomputed by the parent on every poll. */
  remaining: string | null;
}

/** Which button last copied, so its icon can confirm and the status line can say what happened. */
type Copied = 'code' | 'open' | null;

/**
 * What the operator does with a device code: open one page, type one code (M3.5 part F).
 *
 * <p>Two numbered steps rather than a link and a code in a column, because that is the order they are
 * used in, and each carries the one action it needs. The open action copies the code first: the page
 * it opens asks for exactly that, and a browser refuses a clipboard write once focus has moved to the
 * new tab, so the order is not cosmetic.
 *
 * <p>The link is offered only when it is https. The worker already accepts only the vendor's own host,
 * so this is the second lock, placed where a click would follow it.
 */
export default function HarnessSignInCode({ verificationUri, userCode, remaining }: Props) {
  const [copied, setCopied] = useState<Copied>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  const safeUri = isHttps(verificationUri) ? verificationUri : null;

  function confirm(which: Copied) {
    setCopied(which);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(null), 2000);
  }

  function copyCode() {
    void navigator.clipboard?.writeText(userCode);
    confirm('code');
  }

  function copyAndOpen() {
    if (!safeUri) return;
    void navigator.clipboard?.writeText(userCode);
    window.open(safeUri, '_blank', 'noopener,noreferrer');
    confirm('open');
  }

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
              {copied === 'open' ? <Check size={16} /> : <ExternalLink size={16} />}
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
              {copied === 'code' ? <Check size={16} /> : <Copy size={16} />}
            </button>
          </Tooltip>
        </div>
      </li>
    </ol>
    <p className="signin-expiry" role="status">
      <Clock size={14} aria-hidden="true" />
      {statusLine(copied, remaining)}
    </p>
  </div>;
}

/** What just happened, or else how long the code has left. */
function statusLine(copied: Copied, remaining: string | null): string {
  if (copied === 'open') return 'Code copied. Paste it on the page that just opened.';
  if (copied === 'code') return 'Code copied.';
  if (!remaining) return 'The code has expired.';
  return `The code expires in ${remaining}. This panel closes by itself once you approve.`;
}

function isHttps(uri: string): boolean {
  try { return new URL(uri).protocol === 'https:'; }
  catch { return false; }
}
