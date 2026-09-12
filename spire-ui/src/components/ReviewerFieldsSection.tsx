import Select from './Select';
import { conversationLabel } from './ProviderFormModal';
const CONVERSATION_OPTIONS = ['', 'REPORT_ONLY', 'EXPLAIN', 'INTERACTIVE'] as const;
export interface ReviewerFields { conversationLevel: string; authors: string[]; authorDraft: string; }
export default function ReviewerFieldsSection({ reviewer, patchReviewer, addAuthor, removeAuthor }: {
  reviewer: ReviewerFields; patchReviewer: (patch: Partial<ReviewerFields>) => void;
  addAuthor: () => void; removeAuthor: (author: string) => void;
}) {
  return <>
            <label className="field">
              <span>Conversation level</span>
              <Select
                ariaLabel="Conversation level"
                value={reviewer.conversationLevel}
                options={CONVERSATION_OPTIONS.map((lvl) => ({ value: lvl, label: conversationLabel(lvl) }))}
                onChange={(v) => patchReviewer({ conversationLevel: v })}
              />
              <small className="field-hint">
                How deeply the bot converses in this provider&apos;s review threads. Inherit uses the global default.
              </small>
            </label>

            <div className="field">
              <span>May command this bot</span>
              <div className="chip-add">
                <input
                  className="mono"
                  placeholder="stable user id"
                  value={reviewer.authorDraft}
                  onChange={(e) => patchReviewer({ authorDraft: e.target.value })}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      addAuthor();
                    }
                  }}
                />
                <button type="button" className="btn-ghost" onClick={addAuthor}>
                  Add
                </button>
              </div>
              <small className="field-hint">
                The id the forge reports for the user, not the handle: /fix accepts ids only, because a
                handle can change hands and /fix pushes code. Empty means everyone may /review; nobody may /fix.
              </small>
              {reviewer.authors.length > 0 && (
                <div className="chips-edit">
                  {reviewer.authors.map((a) => (
                    <span key={a} className="chip-x">
                      {a}
                      <button type="button" onClick={() => removeAuthor(a)} aria-label={`Remove ${a}`}>
                        ✕
                      </button>
                    </span>
                  ))}
                </div>

              )}
            </div>
  </>;
}
