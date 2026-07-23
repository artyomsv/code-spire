import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { AlertTriangle, ArrowLeft, Eye, FileText, RotateCcw } from 'lucide-react';
import {
  fetchPrompt,
  previewPrompt,
  resetPrompt,
  savePrompt,
  type PromptPreview,
  type PromptView,
} from '../api';
import Tooltip from './Tooltip';
import { KIND_LABELS } from './promptKinds';

// Matches the server's PromptValidation token pattern — used only for the client-side
// "missing required variable" hint; the server is still the source of truth on Save.
const VARIABLE_TOKEN = /\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*}}/g;

function referencedVariables(text: string): Set<string> {
  const names = new Set<string>();
  for (const m of text.matchAll(VARIABLE_TOKEN)) names.add(m[1]);
  return names;
}

/** The per-kind edit page (`/settings/prompts/:kind`): loads the effective template by kind and
 *  renders the editor. A missing/unknown kind surfaces the server's 404 as an inline error. */
export default function PromptDetail() {
  const { kind = '' } = useParams();
  const navigate = useNavigate();
  const [view, setView] = useState<PromptView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    fetchPrompt(kind)
      .then((v) => alive && setView(v))
      .catch((err) => alive && setError(err instanceof Error ? err.message : String(err)))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [kind]);

  return (
    <section className="content">
      <button type="button" className="btn-ghost prompt-back" onClick={() => navigate('/settings/prompts')}>
        <ArrowLeft size={14} aria-hidden="true" /> All prompts
      </button>
      {error && (
        <div className="card" style={{ padding: '14px 18px', color: 'var(--crit)', fontSize: 13 }}>
          {error}
        </div>
      )}
      {!error && (loading || !view) && (
        <div className="card">
          <div style={{ padding: '20px 18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        </div>
      )}
      {view && <PromptEditor key={view.kind} initial={view} />}
    </section>
  );
}

type Busy = 'save' | 'reset' | 'preview' | null;

function PromptEditor({ initial }: { initial: PromptView }) {
  const [system, setSystem] = useState(initial.system);
  const [body, setBody] = useState(initial.body);
  const [customized, setCustomized] = useState(initial.customized);
  const [preview, setPreview] = useState<PromptPreview | null>(null);
  const [busy, setBusy] = useState<Busy>(null);
  const [error, setError] = useState<string | null>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);

  const missingRequired = initial.palette.filter((v) => v.required && !referencedVariables(body).has(v.name));

  function insertVariable(name: string) {
    const token = `{{${name}}}`;
    const el = bodyRef.current;
    if (!el) {
      setBody((b) => b + token);
      return;
    }
    const start = el.selectionStart ?? body.length;
    const end = el.selectionEnd ?? body.length;
    setBody(body.slice(0, start) + token + body.slice(end));
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(start + token.length, start + token.length);
    });
  }

  async function onSave() {
    setBusy('save');
    setError(null);
    try {
      const saved = await savePrompt(initial.kind, system, body);
      setCustomized(saved.customized);
      setPreview(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(null);
    }
  }

  async function onReset() {
    setBusy('reset');
    setError(null);
    try {
      await resetPrompt(initial.kind);
      // Re-fetch the now-effective (default) template rather than reusing `initial`, which still
      // holds the custom text when this kind was customized on load.
      const fresh = await fetchPrompt(initial.kind);
      setSystem(fresh.system);
      setBody(fresh.body);
      setCustomized(fresh.customized);
      setPreview(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(null);
    }
  }

  async function onPreview() {
    setBusy('preview');
    setError(null);
    try {
      setPreview(await previewPrompt(initial.kind, system, body));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="card">
      <div className="head">
        <FileText size={15} aria-hidden="true" />
        <h3>{KIND_LABELS[initial.kind] ?? initial.kind}</h3>
        {customized && (
          <span
            className="badge"
            title={initial.updatedAt ? `Last saved ${new Date(initial.updatedAt).toLocaleString()}` : undefined}
          >
            Custom
          </span>
        )}
      </div>
      <div className="body" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <label className="field">
          <span>Instructions (system)</span>
          <textarea value={system} onChange={(e) => setSystem(e.target.value)} rows={4} disabled={busy !== null} />
          <small className="field-hint">Persona and instructions — variables may only appear in the body.</small>
        </label>

        <label className="field">
          <span>Body</span>
          <textarea
            ref={bodyRef}
            className="mono"
            value={body}
            onChange={(e) => setBody(e.target.value)}
            rows={10}
            disabled={busy !== null}
          />
        </label>

        <div className="chips">
          {initial.palette.map((v) => (
            <button
              key={v.name}
              type="button"
              className="chip"
              onClick={() => insertVariable(v.name)}
              disabled={busy !== null}
              title={v.description}
            >
              {`{{${v.name}}}`}
              {v.required && <span className="n">required</span>}
              {v.fenced && <span className="n">fenced</span>}
            </button>
          ))}
        </div>

        <div className="ctx-preview-item">
          <div className="ctx-preview-title">Always appended to the system instructions (locked)</div>
          <pre className="ctx-preview-body">{initial.lockedSuffixPreview}</pre>
        </div>

        {missingRequired.length > 0 && (
          <div className="modal-msg modal-error">
            <AlertTriangle size={14} aria-hidden="true" /> Missing required variable
            {missingRequired.length > 1 ? 's' : ''}: {missingRequired.map((v) => `{{${v.name}}}`).join(', ')}
          </div>
        )}

        {error && (
          <div className="modal-msg modal-error">
            <AlertTriangle size={14} aria-hidden="true" /> {error}
          </div>
        )}

        <div className="prov-actions" style={{ marginTop: 4 }}>
          <Tooltip label="Preview">
            <button
              type="button"
              className="btn-ghost"
              aria-label="Preview"
              disabled={busy !== null}
              onClick={() => void onPreview()}
            >
              <Eye size={14} aria-hidden="true" />
            </button>
          </Tooltip>
          <button type="button" className="btn-ghost" disabled={busy !== null} onClick={() => void onReset()}>
            <RotateCcw size={14} aria-hidden="true" /> Reset to default
          </button>
          <button
            type="button"
            className="btn"
            disabled={busy !== null || missingRequired.length > 0}
            onClick={() => void onSave()}
          >
            {busy === 'save' ? 'Saving…' : 'Save'}
          </button>
        </div>

        {preview && <PreviewPanel preview={preview} />}
      </div>
    </div>
  );
}

function PreviewPanel({ preview }: { preview: PromptPreview }) {
  return (
    <div className="ctx-preview" style={{ marginTop: 14 }}>
      {preview.errors.length > 0 && (
        <div className="modal-msg modal-error">
          {preview.errors.map((e) => (
            <div key={e}>{e}</div>
          ))}
        </div>
      )}
      <div className="ctx-preview-item">
        <div className="ctx-preview-title">System (with locked suffix)</div>
        <pre className="ctx-preview-body">{preview.system}</pre>
      </div>
      <div className="ctx-preview-item">
        <div className="ctx-preview-title">User (variable slots annotated)</div>
        <pre className="ctx-preview-body">{preview.user}</pre>
      </div>
    </div>
  );
}
