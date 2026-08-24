import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import { AlertTriangle, ArrowLeft, FileText, GitBranch, Globe, RotateCcw } from 'lucide-react';
import { acceptPromptDefault, fetchPrompt, GLOBAL_SCOPE, resetPrompt, savePrompt, type PromptView } from '../api';
import AutoTextarea from './AutoTextarea';
import PromptDriftBanner, { type PromptDrift } from './PromptDriftBanner';
import PromptSamplePicker from './PromptSamplePicker';
import { KIND_LABELS, provenanceLabel } from './promptKinds';

function driftOf(v: PromptView): PromptDrift {
  return {
    baseKnown: v.baseKnown,
    defaultDrifted: v.defaultDrifted,
    currentDefaultSystem: v.currentDefaultSystem,
    currentDefaultBody: v.currentDefaultBody,
    baseSystem: v.baseSystem,
    baseBody: v.baseBody,
  };
}

// Matches the server's PromptValidation token pattern — used only for the client-side
// "missing required variable" hint; the server is still the source of truth on Save.
const VARIABLE_TOKEN = /\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*}}/g;

function referencedVariables(text: string): Set<string> {
  const names = new Set<string>();
  for (const m of text.matchAll(VARIABLE_TOKEN)) names.add(m[1]);
  return names;
}

/** The per-kind edit page (`/settings/prompts/:kind`): loads the effective template by kind and
 *  renders the editor. A missing/unknown kind surfaces the server's 404 as an inline error.
 *  Scope lives in `?scope=` -- read here, threaded through every mutation, and carried back onto
 *  the "All prompts" link so leaving and returning keeps the same repository selected. */
export default function PromptDetail() {
  const { kind = '' } = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const scope = searchParams.get('scope') ?? GLOBAL_SCOPE;
  const [view, setView] = useState<PromptView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    fetchPrompt(kind, scope)
      .then((v) => alive && setView(v))
      .catch((err) => alive && setError(err instanceof Error ? err.message : String(err)))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [kind, scope]);

  const backHref = scope === GLOBAL_SCOPE ? '/settings/prompts' : `/settings/prompts?scope=${encodeURIComponent(scope)}`;

  return (
    <section className="content">
      <button type="button" className="btn-ghost prompt-back" onClick={() => navigate(backHref)}>
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
      {view && <PromptEditor key={`${view.kind}:${view.scope}`} initial={view} />}
    </section>
  );
}

/** Icon per provenance level -- reinforces the text line rather than replacing it. */
function ProvenanceIcon({ inheritedFrom }: { inheritedFrom: PromptView['inheritedFrom'] }) {
  if (inheritedFrom === 'repo') return <GitBranch size={13} aria-hidden="true" />;
  if (inheritedFrom === 'global') return <Globe size={13} aria-hidden="true" />;
  return <FileText size={13} aria-hidden="true" />;
}

/** Unmissable by design: a repo scope showing global's (or the default's) text looks identical to
 *  one with its own override unless this line says otherwise. */
function ProvenanceLine({ scope, inheritedFrom }: { scope: string; inheritedFrom: PromptView['inheritedFrom'] }) {
  return (
    <div className={`prompt-provenance is-${inheritedFrom}`}>
      <ProvenanceIcon inheritedFrom={inheritedFrom} />
      <span>{provenanceLabel(scope, inheritedFrom)}</span>
      {scope !== GLOBAL_SCOPE && <span className="prompt-provenance-scope">{scope}</span>}
    </div>
  );
}

type Busy = 'save' | 'reset' | 'accept' | null;

function PromptEditor({ initial }: { initial: PromptView }) {
  const [system, setSystem] = useState(initial.system);
  const [body, setBody] = useState(initial.body);
  const [customized, setCustomized] = useState(initial.customized);
  // Tracked alongside `customized` rather than re-derived from `initial`, which is stale as soon
  // as save/reset/accept-default changes which row now supplies the effective text.
  const [inheritedFrom, setInheritedFrom] = useState<PromptView['inheritedFrom']>(initial.inheritedFrom);
  const [drift, setDrift] = useState<PromptDrift>(driftOf(initial));
  const [busy, setBusy] = useState<Busy>(null);
  const [error, setError] = useState<string | null>(null);
  // Remounts PromptSamplePicker on save/reset, the same way the old inline preview was cleared —
  // stale sample text and a stale review selection must not survive a system/body change the
  // operator did not ask this preview to reflect.
  const [previewGen, setPreviewGen] = useState(0);
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
      const saved = await savePrompt(initial.kind, system, body, initial.scope);
      setCustomized(saved.customized);
      setInheritedFrom(saved.inheritedFrom);
      setPreviewGen((g) => g + 1);
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
      await resetPrompt(initial.kind, initial.scope);
      // Re-fetch the now-effective (default-or-inherited) template rather than reusing `initial`,
      // which still holds the custom text when this scope was customized on load.
      const fresh = await fetchPrompt(initial.kind, initial.scope);
      setSystem(fresh.system);
      setBody(fresh.body);
      setCustomized(fresh.customized);
      setInheritedFrom(fresh.inheritedFrom);
      setDrift(driftOf(fresh));
      setPreviewGen((g) => g + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(null);
    }
  }

  /** Keep mine: re-stamp the ancestor, leaving `system`/`body` byte-identical. */
  async function onAcceptDefault() {
    setBusy('accept');
    setError(null);
    try {
      await acceptPromptDefault(initial.kind, initial.scope);
      const fresh = await fetchPrompt(initial.kind, initial.scope);
      setInheritedFrom(fresh.inheritedFrom);
      setDrift(driftOf(fresh));
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
      <ProvenanceLine scope={initial.scope} inheritedFrom={inheritedFrom} />
      <div className="body" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <PromptDriftBanner
          drift={drift}
          busy={busy !== null}
          onTakeDefault={() => void onReset()}
          onKeepMine={() => void onAcceptDefault()}
        />

        <label className="field">
          <span>Instructions (system)</span>
          <AutoTextarea value={system} onChange={(e) => setSystem(e.target.value)} rows={4} disabled={busy !== null} />
          <small className="field-hint">Persona and instructions — variables may only appear in the body.</small>
        </label>

        <label className="field">
          <span>Body</span>
          <AutoTextarea
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

        <PromptSamplePicker
          key={previewGen}
          kind={initial.kind}
          system={system}
          body={body}
          disabled={busy !== null}
        />
      </div>
    </div>
  );
}
