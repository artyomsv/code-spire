import { useEffect, useRef, useState } from 'react';
import { registerPr } from '../api';

/**
 * Extract workspace / repo / PR from any Bitbucket PR URL, ignoring the host —
 * so proxied hosts (e.g. company MCAS: bitbucket.org.mcas.ms) and trailing path
 * or query segments still parse.
 */
function parsePrUrl(raw: string): { workspace: string; slug: string; pr: string } | null {
  // {workspace}/{slug}/<pr-segment>/{id}: Bitbucket "pull-requests"/"pullrequests" or GitHub "pull".
  // Mirrors the backend ManualRegisterResource.PR_URL parser.
  const m = raw.match(/([^/\s?#]+)\/([^/\s?#]+)\/(?:pull-requests|pullrequests|pull)\/(\d+)/);
  return m ? { workspace: m[1], slug: m[2], pr: m[3] } : null;
}

/**
 * Parse the PR # field into a positive integer, or null when it isn't one —
 * `Number('abc')` is NaN, which JSON-serializes to null and breaks the backend.
 */
export function parsePrNumber(raw: string): number | null {
  const trimmed = raw.trim();
  if (!/^\d+$/.test(trimmed)) return null;
  const n = Number(trimmed);
  return Number.isSafeInteger(n) && n > 0 ? n : null;
}

export default function RegisterPrDialog({ onClose }: { onClose: () => void }) {
  const [url, setUrl] = useState('');
  const [workspace, setWorkspace] = useState('');
  const [slug, setSlug] = useState('');
  const [pr, setPr] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(
    () => () => {
      if (closeTimer.current) clearTimeout(closeTimer.current);
    },
    [],
  );

  function onUrlChange(value: string) {
    setUrl(value);
    const parsed = parsePrUrl(value);
    if (parsed) {
      setWorkspace(parsed.workspace);
      setSlug(parsed.slug);
      setPr(parsed.pr);
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!workspace.trim() || !slug.trim() || !pr.trim()) {
      setError('Paste a pull request URL, or fill in workspace, repository and PR #.');
      return;
    }
    const prNumber = parsePrNumber(pr);
    if (prNumber === null) {
      setError('PR # must be a positive whole number.');
      return;
    }
    setBusy(true);
    setError(null);
    setOk(null);
    try {
      const result = await registerPr({ workspace: workspace.trim(), slug: slug.trim(), pr: prNumber });
      setOk(result.reviewId);
      // let the live list show the new row, then close
      closeTimer.current = setTimeout(onClose, 1000);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
        <div className="modal-head">
          <h3>Register a pull request</h3>
          <button className="iconbtn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        <form className="modal-body" onSubmit={submit}>
          <label className="field">
            <span>Pull request URL</span>
            <input
              className="mono"
              placeholder="https://github.com/owner/repo/pull/123"
              value={url}
              onChange={(e) => onUrlChange(e.target.value)}
              autoFocus
            />
          </label>
          <div className="modal-or">fills in the fields below — edit if needed</div>
          <div className="field-row">
            <label className="field">
              <span>Workspace</span>
              <input
                className="mono"
                placeholder="workspace"
                value={workspace}
                onChange={(e) => setWorkspace(e.target.value)}
              />
            </label>
            <label className="field">
              <span>Repository</span>
              <input
                className="mono"
                placeholder="repo-slug"
                value={slug}
                onChange={(e) => setSlug(e.target.value)}
              />
            </label>
            <label className="field field-pr">
              <span>PR #</span>
              <input
                className="mono"
                placeholder="123"
                inputMode="numeric"
                value={pr}
                onChange={(e) => setPr(e.target.value)}
              />
            </label>
          </div>
          {error && <div className="modal-msg modal-error">{error}</div>}
          {ok && <div className="modal-msg modal-ok">Registered {ok}</div>}
          <div className="modal-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn" disabled={busy}>
              {busy ? 'Registering…' : 'Register'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
