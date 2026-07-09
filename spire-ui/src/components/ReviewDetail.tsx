import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { deleteReview, fetchReviewDetail, rerunReview, type ReviewDetail as ReviewDetailData, type ReviewSummary } from '../api';
import { ExternalLink, RotateCw, Trash2 } from 'lucide-react';
import Tooltip from './Tooltip';
import { eventsCard, findingsCard, metaCard, openInLabel, outcomeBadge, safeHttpUrl, stageLabel, STATUS_LABEL, stepper, usageCard } from '../render';
import ConfirmDialog from './ConfirmDialog';

interface Props {
  reviews: ReviewSummary[];
}

export default function ReviewDetail({ reviews }: Props) {
  const { workspace, slug, pr } = useParams();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<ReviewDetailData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [confirmRerun, setConfirmRerun] = useState(false);

  // Monotonic request id: a response only applies while it is still the latest
  // load for the current params — otherwise navigating A→B can let A's slower
  // response clobber B (mirrors the `active` pattern in useLiveReviews).
  const requestSeq = useRef(0);

  const load = useCallback(() => {
    if (!workspace || !slug || !pr) return;
    const seq = ++requestSeq.current;
    fetchReviewDetail(workspace, slug, pr)
      .then((d) => {
        if (seq !== requestSeq.current) return;
        setDetail(d);
        setError(null);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (seq !== requestSeq.current) return;
        setError(e instanceof Error ? e.message : 'Failed to load review');
        setLoading(false);
      });
  }, [workspace, slug, pr]);

  useEffect(() => {
    setLoading(true);
    setDetail(null);
    load();
    window.scrollTo(0, 0);
    return () => {
      // Invalidate in-flight responses on param change / unmount.
      requestSeq.current++;
    };
  }, [load]);

  // When a live summary update arrives for this review, refresh the detail.
  const summary = reviews.find(
    (r) => r.workspace === workspace && r.slug === slug && String(r.pr) === pr,
  );
  const summaryUpdatedAt = summary?.updatedAt;
  const prevUpdated = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (summaryUpdatedAt === undefined) return;
    if (prevUpdated.current !== undefined && prevUpdated.current !== summaryUpdatedAt) {
      load();
    }
    prevUpdated.current = summaryUpdatedAt;
  }, [summaryUpdatedAt, load]);

  if (error) {
    return (
      <section className="content" id="view-detail">
        <Link className="back" to="/">
          ← All reviews
        </Link>
        <div style={{ color: 'var(--crit)', fontSize: 13 }}>{error}</div>
      </section>
    );
  }

  if (loading || !detail) {
    return (
      <section className="content" id="view-detail">
        <Link className="back" to="/">
          ← All reviews
        </Link>
        <div style={{ color: 'var(--text-3)', fontSize: 13 }}>Loading…</div>
      </section>
    );
  }

  const r = detail;
  const prUrl = safeHttpUrl(r.htmlUrl);

  return (
    <section className="content" id="view-detail">
      <Link className="back" to="/">
        ← All reviews
      </Link>
      <div className="dhead">
        <div className="maintitle">
          <h2>{r.title}</h2>
          <div className="meta-line">
            <span className="repo">
              {r.repo}#{r.pr}
            </span>
            {outcomeBadge(r.status, r.findings, r.blockerCount)}
            <span className="sep">·</span>
            <span>@{r.author}</span>
            <span className="sep">·</span>
            <span>
              {r.branch} → {r.base}
            </span>
            <span className="sep">·</span>
            <span className="sha">{r.sha}</span>
          </div>
        </div>
        <div className="actions">
          {prUrl && (
            <Tooltip label={openInLabel(r)}>
              <a
                className="icon-btn"
                href={prUrl}
                target="_blank"
                rel="noreferrer"
                aria-label={openInLabel(r)}
              >
                <ExternalLink size={16} />
              </a>
            </Tooltip>
          )}
          {(r.status === 'completed' || r.status === 'failed') && (
            <Tooltip label="Re-run review on the same commit">
              <button
                className="icon-btn rerun"
                aria-label="Re-run review"
                onClick={() => setConfirmRerun(true)}
              >
                <RotateCw size={16} />
              </button>
            </Tooltip>
          )}
          <Tooltip label="Delete review">
            <button
              className="icon-btn danger"
              aria-label="Delete review"
              onClick={() => setConfirmDelete(true)}
            >
              <Trash2 size={16} />
            </button>
          </Tooltip>
        </div>
      </div>

      {confirmRerun && (
        <ConfirmDialog
          title="Re-run this review?"
          message={
            <>
              <p>
                Re-runs the model on{' '}
                <span className="mono">
                  {r.repo}#{r.pr}
                </span>{' '}
                at the same commit and posts fresh comments.
              </p>
              <p>Previously posted comments on the pull request are not removed.</p>
            </>
          }
          confirmLabel="Re-run"
          busyLabel="Starting…"
          onConfirm={async () => {
            await rerunReview(r.workspace, r.slug, r.pr);
            load(); // reflect the in-progress state immediately (live updates follow)
          }}
          onClose={() => setConfirmRerun(false)}
        />
      )}

      {confirmDelete && (
        <ConfirmDialog
          title="Delete this review?"
          message={
            <>
              <p>
                This permanently deletes the review for{' '}
                <span className="mono">
                  {r.repo}#{r.pr}
                </span>{' '}
                — its findings, timeline and event stream. This cannot be undone.
              </p>
              <p>The pull request itself is not affected.</p>
            </>
          }
          confirmLabel="Delete review"
          busyLabel="Deleting…"
          danger
          onConfirm={async () => {
            await deleteReview(r.workspace, r.slug, r.pr);
            navigate('/');
          }}
          onClose={() => setConfirmDelete(false)}
        />
      )}

      <div className="card">
        <div className="head">
          <span className="k">//</span>
          <h3>Pipeline</h3>
          <span className="badge">
            {r.status === 'reviewing' ? 'running · ' + stageLabel(r.stage) : STATUS_LABEL[r.status]}
          </span>
        </div>
        {stepper(r)}
      </div>

      <div className="grid2" style={{ marginTop: 18 }}>
        <div>
          {findingsCard(r)}
          {eventsCard(r)}
        </div>
        <div>
          {usageCard(r)}
          {metaCard(r)}
        </div>
      </div>
    </section>
  );
}
