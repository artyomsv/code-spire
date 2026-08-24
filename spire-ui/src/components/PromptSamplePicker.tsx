import { useEffect, useState } from 'react';
import { AlertTriangle, Eye } from 'lucide-react';
import { fetchReviews, previewPrompt, type PromptPreview, type ReviewSummary } from '../api';
import Select from './Select';
import Tooltip from './Tooltip';

const NO_SAMPLE = '';

interface PromptSamplePickerProps {
  kind: string;
  system: string;
  body: string;
  disabled?: boolean;
}

/**
 * Runs a candidate prompt against a REAL review the deployment already has, or against no review
 * at all. "No sample" is the default and stays fully functional even when the review list can't be
 * loaded — the annotated preview needs no sample, and a deployment with zero reviews (or a flaky
 * reviews endpoint) must not lose the ability to preview at all.
 *
 * Owns the review list, the selection, the preview call and the result panel; `PromptDetail` only
 * supplies the candidate `system`/`body` text being edited.
 */
export default function PromptSamplePicker({ kind, system, body, disabled }: PromptSamplePickerProps) {
  const [reviews, setReviews] = useState<ReviewSummary[]>([]);
  const [reviewId, setReviewId] = useState(NO_SAMPLE);
  const [preview, setPreview] = useState<PromptPreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    // Best-effort: a failed fetch leaves the picker at its "no sample" default rather than
    // blocking the preview feature on the reviews list being reachable.
    fetchReviews()
      .then((r) => alive && setReviews(r))
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  async function onPreview() {
    setBusy(true);
    setError(null);
    try {
      setPreview(await previewPrompt(kind, system, body, reviewId || undefined));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  const options = [
    { value: NO_SAMPLE, label: 'No sample — show variable slots' },
    ...reviews.map((r) => ({ value: r.id, label: `${r.workspace}/${r.slug}#${r.pr}` })),
  ];

  return (
    <div className="prompt-sample-picker">
      <div className="prov-actions" style={{ marginTop: 4 }}>
        <label className="field">
          <span>Sample</span>
          <Select
            ariaLabel="Sample review"
            value={reviewId}
            options={options}
            onChange={setReviewId}
            disabled={disabled || busy}
          />
        </label>
        <Tooltip label="Preview">
          <button
            type="button"
            className="btn-ghost"
            aria-label="Preview"
            disabled={disabled || busy}
            onClick={() => void onPreview()}
          >
            <Eye size={14} aria-hidden="true" />
          </button>
        </Tooltip>
      </div>

      {error && (
        <div className="modal-msg modal-error">
          <AlertTriangle size={14} aria-hidden="true" /> {error}
        </div>
      )}

      {preview && <PreviewPanel preview={preview} />}
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
      {preview.unavailableReason && <div className="field-hint">{preview.unavailableReason}</div>}
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
