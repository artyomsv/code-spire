import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, FileText } from 'lucide-react';
import { fetchPrompts, type PromptView } from '../api';
import { KIND_LABELS } from './promptKinds';

// One-line purpose per kind, shown under the label on the list page.
const KIND_BLURBS: Record<string, string> = {
  review: 'The main code-review prompt — what to look for and how findings are reported.',
  reconcile: 'Re-review verdicts — judging prior findings against the follow-up changes.',
  followup: 'In-thread replies when an author responds to a review comment.',
};

export default function PromptsSettings() {
  const navigate = useNavigate();
  const [prompts, setPrompts] = useState<PromptView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchPrompts()
      .then((list) => alive && setPrompts(list))
      .catch((err) => alive && setError(err instanceof Error ? err.message : String(err)))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, []);

  return (
    <section className="content">
      {error && (
        <div className="card" style={{ padding: '14px 18px', color: 'var(--crit)', fontSize: 13, marginBottom: 18 }}>
          {error}
        </div>
      )}
      <div className="card">
        <div className="prov-head">
          <h3 className="prov-title">Prompts</h3>
        </div>
        <p className="prov-note">
          The prompts Code Spire sends to the LLM. Edit the instructions and place variables — the
          security fence and output format are always enforced. Select a prompt to edit.
        </p>
        {loading && prompts.length === 0 ? (
          <div style={{ padding: '18px', color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
        ) : (
          <div className="prompt-list">
            {prompts.map((p) => (
              <button
                key={p.kind}
                type="button"
                className="prompt-row"
                onClick={() => navigate(`/settings/prompts/${p.kind}`)}
              >
                <FileText size={16} aria-hidden="true" className="prompt-row-icon" />
                <div className="prompt-row-main">
                  <div className="prompt-row-title">
                    {KIND_LABELS[p.kind] ?? p.kind}
                    <span className={`prompt-tag ${p.customized ? 'is-custom' : ''}`}>
                      {p.customized ? 'Custom' : 'Default'}
                    </span>
                  </div>
                  <div className="prompt-row-sub">{KIND_BLURBS[p.kind] ?? ''}</div>
                </div>
                <ChevronRight size={16} aria-hidden="true" className="prompt-row-chev" />
              </button>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
