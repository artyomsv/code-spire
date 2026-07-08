import { useEffect, useRef, useState } from 'react';
import { registerPr, resolvePrUrl, type ResolvedUrl } from '../api';

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

export default function RegisterPrDialog({
  onClose,
  onRegistered,
}: {
  onClose: () => void;
  onRegistered?: () => void;
}) {
  const [url, setUrl] = useState('');
  const [workspace, setWorkspace] = useState('');
  const [slug, setSlug] = useState('');
  const [pr, setPr] = useState('');
  const [resolved, setResolved] = useState<ResolvedUrl | null>(null);
  const [urlUnrecognised, setUrlUnrecognised] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState<string | null>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const resolveTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const resolveSeq = useRef(0);
  useEffect(
    () => () => {
      if (closeTimer.current) clearTimeout(closeTimer.current);
      if (resolveTimer.current) clearTimeout(resolveTimer.current);
    },
    [],
  );

  // The URL is parsed on the backend (one source of truth for the URL shapes),
  // which also reports which registered provider will handle it. Debounced, with
  // a sequence guard so a slow response can't overwrite a newer keystroke.
  function onUrlChange(value: string) {
    setUrl(value);
    if (resolveTimer.current) clearTimeout(resolveTimer.current);
    const trimmed = value.trim();
    if (!trimmed) {
      setResolved(null);
      setUrlUnrecognised(false);
      return;
    }
    const seq = ++resolveSeq.current;
    resolveTimer.current = setTimeout(() => {
      void resolvePrUrl(trimmed)
        .then((r) => {
          if (seq !== resolveSeq.current) return; // superseded by a newer keystroke
          setResolved(r);
          setUrlUnrecognised(false);
          setWorkspace(r.workspace);
          setSlug(r.slug);
          setPr(String(r.pr));
        })
        .catch(() => {
          if (seq !== resolveSeq.current) return;
          setResolved(null);
          setUrlUnrecognised(true); // recognised nothing — tell the user why it stayed blank
        });
    }, 300);
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
      onRegistered?.();
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
              placeholder="https://gitlab.com/group/project/-/merge_requests/123"
              value={url}
              onChange={(e) => onUrlChange(e.target.value)}
              autoFocus
            />
          </label>
          <div className="modal-or">fills in the fields below — edit if needed</div>
          {resolved &&
            (resolved.providerRegistered ? (
              <div className="resolve-hint ok">
                Will use {resolved.providerType} · {resolved.providerName}
              </div>
            ) : (
              <div className="resolve-hint warn">
                No provider registered for “{resolved.workspace}” — add one under Settings → Providers
              </div>
            ))}
          {!resolved && urlUnrecognised && (
            <div className="resolve-hint warn">
              Not a PR/MR URL — include the number, e.g. …/-/merge_requests/1, …/pull/1, or
              …/pull-requests/1
            </div>
          )}
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
