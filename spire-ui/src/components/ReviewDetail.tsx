import { useCallback, useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { invalidateLive } from '../liveCache';
import { fetchReviewDetail, reviewDetailKey, type PostedVerdict, type ReviewDetail as ReviewDetailData, type ReviewSummary } from '../api';
import { archivedBadge, CONTEXT_STAGE, findingsCard, generalDiscussionCard, metaCard, prStateBadge, stageLabel, STATUS_LABEL, statusCell, stepper } from '../render';
import EventStream from './EventStream';
import ContextCard from './ContextCard';
import ReviewActions from './ReviewActions';
import ReviewCostCard from './ReviewCostCard';

interface Props {
  reviews: ReviewSummary[];
}

const POSTED_LABEL: Record<PostedVerdict, string> = { approved: 'Approved', changes_requested: 'Changes requested', commented: 'Comment' };

/** The link comes from the forge API; only a web address is rendered as one. */
const isWebAddress = (url: string) => /^https?:\/\//i.test(url);

/** How the forge holds the last posted run (ADR-047); nothing for the legacy comment path or another forge. */
function postedAs(r: ReviewDetailData) {
  if (!r.postedVerdict) return null;
  return <p className="posted-as">Posted as <strong>{POSTED_LABEL[r.postedVerdict]}</strong>
    {r.reviewUrl && isWebAddress(r.reviewUrl) && <> · <a href={r.reviewUrl} target="_blank" rel="noreferrer">open the review</a></>}</p>;
}

export default function ReviewDetail({ reviews }: Props) {
  const { workspace, slug, pr } = useParams();
  const client = useQueryClient();
  // One cache entry per review, so a slower answer for review A can never land under review B's address.
  const read = useQuery({
    queryKey: reviewDetailKey(workspace ?? '', slug ?? '', pr ?? ''),
    queryFn: () => fetchReviewDetail(workspace ?? '', slug ?? '', pr ?? ''),
    enabled: !!workspace && !!slug && !!pr,
  });
  const detail: ReviewDetailData | null = read.data ?? null;
  const error = read.error ? (read.error instanceof Error ? read.error.message : 'Failed to load review') : null;

  const load = useCallback(
    () => void invalidateLive(client, reviewDetailKey(workspace ?? '', slug ?? '', pr ?? '')),
    [client, workspace, slug, pr],
  );

  useEffect(() => {
    window.scrollTo(0, 0);
  }, [workspace, slug, pr]);

  // When a live summary update arrives for this review, refresh the detail. The first summary seen for a review is
  // where it starts, not a change: opening review B must not re-read B because A's summary differed.
  const summary = reviews.find(
    (r) => r.workspace === workspace && r.slug === slug && String(r.pr) === pr,
  );
  const summaryUpdatedAt = summary?.updatedAt;
  const reviewId = `${workspace}/${slug}/${pr}`;
  const seen = useRef<{ review: string; updatedAt: string } | null>(null);
  useEffect(() => {
    if (summaryUpdatedAt === undefined) return;
    const previous = seen.current;
    seen.current = { review: reviewId, updatedAt: summaryUpdatedAt };
    if (previous && previous.review === reviewId && previous.updatedAt !== summaryUpdatedAt) load();
  }, [reviewId, summaryUpdatedAt, load]);

  if (error && !detail) {
    return (
      <section className="content" id="view-detail">
        <Link className="back" to="/">
          ← All reviews
        </Link>
        <div style={{ color: 'var(--crit)', fontSize: 13 }}>{error}</div>
      </section>
    );
  }

  if (!detail) {
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

  return (
    <section className="content" id="view-detail">
      <Link className="back" to="/">
        ← All reviews
      </Link>
      {/* A failed re-read keeps the last detail on screen and says why it may be out of date. */}
      {error && (
        <div role="alert" style={{ color: 'var(--crit)', fontSize: 13, marginBottom: 12 }}>
          {error}
        </div>
      )}
      <div className="dhead">
        <div className="maintitle">
          <h2>{r.title}</h2>
          <div className="meta-line">
            <span className="repo">
              {r.repo}#{r.pr}
            </span>
            {statusCell({
              status: r.status,
              findings: r.openFindings,
              blockerCount: r.openBlockers,
              answering: r.answering,
              degraded: r.degraded,
            })}
            {prStateBadge(r.prState)}
            {archivedBadge(r.archivedAt)}
            <span className="sep">·</span>
            <span>@{r.author}</span>
            <span className="sep">·</span>
            <span>
              {r.branch} → {r.base}
            </span>
            <span className="sep">·</span>
            <span className="sha">{r.sha}</span>
          </div>
          {postedAs(r)}
        </div>
        <ReviewActions review={r} onChanged={load} />
      </div>

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
          {/* The socket reports stage progress; the assembled context itself lives in the worker
              behind its own endpoint, so the card has to be told when there is something to
              re-fetch. STAGES[2] is "Context", and its completion is that moment. */}
          <ContextCard
            workspace={r.workspace}
            slug={r.slug}
            pr={r.pr}
            sha={r.sha}
            contextReady={r.stages[CONTEXT_STAGE] === 'done'}
          />
          {generalDiscussionCard(r)}
          <EventStream r={r} />
        </div>
        <div>
          {metaCard(r)}
          <ReviewCostCard lines={r.chargeLines} unpricedCalls={r.unpricedCalls} />
        </div>
      </div>
    </section>
  );
}
