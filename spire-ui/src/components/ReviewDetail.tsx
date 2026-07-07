import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { fetchReviewDetail, type ReviewDetail as ReviewDetailData, type ReviewSummary } from '../api';
import { eventsCard, findingsCard, metaCard, openInLabel, pill, STAGES, STATUS_LABEL, stepper, usageCard } from '../render';

interface Props {
  reviews: ReviewSummary[];
}

export default function ReviewDetail({ reviews }: Props) {
  const { workspace, slug, pr } = useParams();
  const [detail, setDetail] = useState<ReviewDetailData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    if (!workspace || !slug || !pr) return;
    fetchReviewDetail(workspace, slug, pr)
      .then((d) => {
        setDetail(d);
        setError(null);
        setLoading(false);
      })
      .catch((e: unknown) => {
        setError(e instanceof Error ? e.message : 'Failed to load review');
        setLoading(false);
      });
  }, [workspace, slug, pr]);

  useEffect(() => {
    setLoading(true);
    setDetail(null);
    load();
    window.scrollTo(0, 0);
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
            {pill(r.status)}
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
          <a className="btn-ghost" href={r.htmlUrl} target="_blank" rel="noreferrer">
            {openInLabel(r.htmlUrl)} ↗
          </a>
          {r.status === 'failed' && <button className="btn">Re-run review</button>}
        </div>
      </div>

      <div className="card">
        <div className="head">
          <span className="k">//</span>
          <h3>Pipeline</h3>
          <span className="badge">
            {r.status === 'reviewing' ? 'running · ' + STAGES[r.stage] : STATUS_LABEL[r.status]}
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
