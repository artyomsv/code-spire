import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  generalSettingsKey,
  getCapSettings,
  getConversationSettings,
  getReviewSettings,
  type ConversationSettings as ConversationSettingsShape,
  type ReviewSettings as ReviewSettingsShape,
} from '../api';
import { getReviewDiffLimit } from '../reviewDiffLimitApi';
import CapFields, { initialCapFields, type CapFieldValues } from './CapSettings';
import ConversationFields from './ConversationSettings';
import ReviewFields from './ReviewSettings';
import ReviewDiffLimitField, { diffLimitText } from './ReviewDiffLimitField';
import { saveChangedGroups, validateGeneral, type GeneralForm } from './generalSettingsForm';

/**
 * General preferences. The page owns the state and the single Save for BOTH groups — they are two
 * endpoints, but that is an implementation detail an operator should not have to notice as two buttons.
 * Only the group that actually changed is written.
 *
 * The groups stay visually separate (plain headings, not nested panels) because their retry budgets
 * differ: a review that exhausts its attempts is reported as failed, while a follow-up answer is
 * dead-lettered for replay.
 */
export default function SettingsGeneral() {
  const [review, setReview] = useState<ReviewSettingsShape | null>(null);
  const [conversation, setConversation] = useState<ConversationSettingsShape | null>(null);
  const [caps, setCaps] = useState<CapFieldValues | null>(null);
  // Kept as text, as the cap fields are: a blank field must send null ("as much as the model allows"), never 0.
  const [diffLimit, setDiffLimit] = useState<string | null>(null);
  const [loaded, setLoaded] = useState<GeneralForm | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const read = useQuery({
    queryKey: generalSettingsKey,
    queryFn: async () => {
      const [review, conversation, caps, diffLimit] = await Promise.all([
        getReviewSettings(),
        getConversationSettings(),
        getCapSettings(),
        getReviewDiffLimit(),
      ]);
      return { review, conversation, caps, diffLimit };
    },
  });
  // The form fills once, from a read made on this visit: a cached copy may be out of date, and a save would send it
  // back (PR 357, M-1). A later re-read (a reconnect) never overwrites what the operator is typing.
  const filled = useRef(false);
  const fresh = read.isFetchedAfterMount && read.isSuccess ? read.data : undefined;
  useEffect(() => {
    if (!fresh || filled.current) return;
    filled.current = true;
    const capFields = initialCapFields(fresh.caps);
    const limitText = diffLimitText(fresh.diffLimit);
    setReview(fresh.review);
    setConversation(fresh.conversation);
    setCaps(capFields);
    setDiffLimit(limitText);
    setLoaded({ review: fresh.review, conversation: fresh.conversation, caps: capFields, diffLimit: limitText });
  }, [fresh]);
  const readError = read.error ? (read.error instanceof Error ? read.error.message : String(read.error)) : null;

  function currentForm(): GeneralForm | null {
    if (!review || !conversation || !caps || diffLimit === null) return null;
    return { review, conversation, caps, diffLimit };
  }

  function showStored(stored: GeneralForm) {
    setReview(stored.review);
    setConversation(stored.conversation);
    setCaps(stored.caps);
    setDiffLimit(stored.diffLimit);
    setLoaded(stored);
  }

  async function save() {
    const form = currentForm();
    if (!form || !loaded || busy) return;
    setSaved(false);
    const fieldError = validateGeneral(form);
    if (fieldError) {
      setError(fieldError);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      showStored(await saveChangedGroups(form, loaded));
      setSaved(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="content">
      <div className="card">
        <div className="prov-head">
          <h2 className="prov-title">General</h2>
        </div>
        <div style={{ padding: '4px 18px 18px' }}>
          {!review || !conversation || !caps || diffLimit === null ? (
            error ?? readError ? <p className="prov-error">{error ?? readError}</p> : <p className="prov-sub">Loading…</p>
          ) : (
            <>
              <h3 className="settings-group">Code review</h3>
              <ReviewFields
                value={review}
                disabled={busy}
                onChange={(v) => {
                  setReview(v);
                  setSaved(false);
                }}
              />
              <ReviewDiffLimitField
                value={diffLimit}
                disabled={busy}
                onChange={(v) => {
                  setDiffLimit(v);
                  setSaved(false);
                }}
              />

              <h3 className="settings-group">Conversation</h3>
              <ConversationFields
                value={conversation}
                disabled={busy}
                onChange={(v) => {
                  setConversation(v);
                  setSaved(false);
                }}
              />

              <h3 className="settings-group">Limits</h3>
              <CapFields
                value={caps}
                disabled={busy}
                onChange={(v) => {
                  setCaps(v);
                  setSaved(false);
                }}
              />

              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 18 }}>
                <button type="button" className="btn" disabled={busy} onClick={() => void save()}>
                  {busy ? 'Saving…' : 'Save'}
                </button>
                {saved && !busy && <div className="modal-msg modal-ok">Saved</div>}
              </div>
              {error && <p className="prov-error">{error}</p>}
            </>
          )}
        </div>
      </div>
    </section>
  );
}
