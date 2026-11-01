import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Bot } from 'lucide-react';
import { getRuns, runsForReviewKey } from '../api';
import { invalidateLive } from '../liveCache';
import { runStatusLabel, runStatusPill, shortRunId } from './Runs';

interface Props {
  reviewId: string;
  /** The review entry's own change time. A change re-reads the run: there is no socket for one review's runs. */
  updatedAt: string;
}

/**
 * The run reviewing this pull request on a harness (harness reviews, step 5), so its transcript is one click away.
 * An API review has no such run, and shows nothing.
 */
export default function HarnessRunLink({ reviewId, updatedAt }: Props) {
  const client = useQueryClient();
  const read = useQuery({ queryKey: runsForReviewKey(reviewId), queryFn: () => getRuns({ reviewId, kind: 'REVIEW', limit: 1 }) });
  // The first change time seen is where the page starts, not a change.
  const seen = useRef<{ review: string; updatedAt: string } | null>(null);
  useEffect(() => {
    const previous = seen.current;
    seen.current = { review: reviewId, updatedAt };
    if (previous && previous.review === reviewId && previous.updatedAt !== updatedAt) void invalidateLive(client, runsForReviewKey(reviewId));
  }, [client, reviewId, updatedAt]);
  const run = read.data?.[0];
  if (!run) return null;
  return <p className="posted-as">
    <Bot size={12} aria-hidden="true" /> <span>Harness run</span>{' '}
    <Link to={`/runs/${encodeURIComponent(run.runId)}`} className="mono" title={run.runId}>{shortRunId(run.runId)}</Link>{' '}
    <span className={`pill ${runStatusPill(run.status)}`}>{runStatusLabel(run.status)}</span>
  </p>;
}
