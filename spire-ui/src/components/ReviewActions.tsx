import { useState } from 'react';
import { Archive, ArchiveRestore, ExternalLink, RotateCw } from 'lucide-react';
import { archiveReview, rerunReview, unarchiveReview, type ReviewDetail } from '../api';
import { openInLabel, safeHttpUrl } from '../render';
import { canAdminister } from '../auth';
import { useMe } from '../hooks/useMe';
import ConfirmDialog from './ConfirmDialog';
import Tooltip from './Tooltip';

interface Props {
  review: ReviewDetail;
  /** Reload the detail after an action changes it — the buttons below then reflect the new state. */
  onChanged: () => void;
}

/**
 * The detail page's action bar.
 *
 * <p>Everything except the link out is admin: a re-run spends money on a fresh model call, and
 * archiving retires the pull request so no future push bills the operator again. Hidden from a
 * viewer rather than disabled — the API refuses them either way, so a control that can only fail is
 * noise.
 */
export default function ReviewActions({ review, onChanged }: Props) {
  const [confirmArchive, setConfirmArchive] = useState(false);
  const [confirmUnarchive, setConfirmUnarchive] = useState(false);
  const [confirmRerun, setConfirmRerun] = useState(false);
  const admin = canAdminister(useMe().me);

  const prUrl = safeHttpUrl(review.htmlUrl);
  const archived = review.archivedAt !== null && review.archivedAt !== undefined;
  const rerunnable = review.status === 'completed' || review.status === 'failed';

  return (
    <>
      <div className="actions">
        {prUrl && (
          <Tooltip label={openInLabel(review)}>
            <a className="icon-btn" href={prUrl} target="_blank" rel="noreferrer" aria-label={openInLabel(review)}>
              <ExternalLink size={16} />
            </a>
          </Tooltip>
        )}
        {/* Withdrawn while archived: the pull request is retired, so the API answers 409. */}
        {admin && !archived && rerunnable && (
          <Tooltip label="Re-run review on the same commit">
            <button className="icon-btn rerun" aria-label="Re-run review" onClick={() => setConfirmRerun(true)}>
              <RotateCw size={16} />
            </button>
          </Tooltip>
        )}
        {admin && !archived && (
          <Tooltip label="Archive review">
            <button className="icon-btn" aria-label="Archive review" onClick={() => setConfirmArchive(true)}>
              <Archive size={16} />
            </button>
          </Tooltip>
        )}
        {admin && archived && (
          <Tooltip label="Unarchive review">
            <button className="icon-btn" aria-label="Unarchive review" onClick={() => setConfirmUnarchive(true)}>
              <ArchiveRestore size={16} />
            </button>
          </Tooltip>
        )}
      </div>

      {confirmRerun && (
        <ConfirmDialog
          title="Re-run this review?"
          message={
            <>
              <p>
                Re-runs the model on{' '}
                <span className="mono">
                  {review.repo}#{review.pr}
                </span>{' '}
                at the same commit and posts fresh comments.
              </p>
              <p>Previously posted comments on the pull request are not removed.</p>
            </>
          }
          confirmLabel="Re-run"
          busyLabel="Starting…"
          onConfirm={async () => {
            await rerunReview(review.workspace, review.slug, review.pr);
            onChanged(); // reflect the in-progress state immediately (live updates follow)
          }}
          onClose={() => setConfirmRerun(false)}
        />
      )}

      {confirmArchive && (
        <ConfirmDialog
          title="Archive this review?"
          message={
            <>
              <p>
                Removes{' '}
                <span className="mono">
                  {review.repo}#{review.pr}
                </span>{' '}
                from the reviews list. Its findings, timeline and recorded model spend are kept, and
                this page keeps working — tick <em>Show archived</em> to find it again.
              </p>
              <p>
                The pull request is retired: no further reviews run for it, and a push, a re-run or a{' '}
                <span className="mono">/review</span> comment will not start one. Unarchive to undo.
              </p>
            </>
          }
          confirmLabel="Archive review"
          busyLabel="Archiving…"
          onConfirm={async () => {
            await archiveReview(review.workspace, review.slug, review.pr);
            onChanged();
          }}
          onClose={() => setConfirmArchive(false)}
        />
      )}

      {confirmUnarchive && (
        <ConfirmDialog
          title="Unarchive this review?"
          message={
            <p>
              Returns{' '}
              <span className="mono">
                {review.repo}#{review.pr}
              </span>{' '}
              to the reviews list and lifts its retirement, so a push or a re-run will start a paid
              review again.
            </p>
          }
          confirmLabel="Unarchive review"
          busyLabel="Restoring…"
          onConfirm={async () => {
            await unarchiveReview(review.workspace, review.slug, review.pr);
            onChanged();
          }}
          onClose={() => setConfirmUnarchive(false)}
        />
      )}
    </>
  );
}
