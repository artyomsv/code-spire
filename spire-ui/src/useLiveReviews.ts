import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchReviews, type ReviewSummary } from './api';
import { isLeavingForAuth } from './auth';
import { useLiveSocket } from './hooks/useLiveSocket';
import { updateLive, upsertBy, writeLive } from './liveCache';

/** Live reviews: the REST snapshot and the `/api/ws/reviews` feed write the same entry. */
export const REVIEWS_KEY = ['reviews', 'live'] as const;
/** Archived reviews: REST only, read while "Show archived" is checked. */
export const ARCHIVED_REVIEWS_KEY = ['reviews', 'archived'] as const;

function sortReviews(list: ReviewSummary[]): ReviewSummary[] {
  return [...list].sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt));
}

/**
 * Minimal wire validation: a summary without a string `id` would produce
 * undefined/duplicate React keys and unmergeable rows — drop it instead of
 * trusting the payload blindly.
 */
function isReviewSummary(d: unknown): d is ReviewSummary {
  return typeof d === 'object' && d !== null && typeof (d as { id?: unknown }).id === 'string';
}

/** A `{ removed: reviewId }` push tells us a review was deleted — drop its row. */
function isRemoval(d: unknown): d is { removed: string } {
  return typeof d === 'object' && d !== null && typeof (d as { removed?: unknown }).removed === 'string';
}

export interface LiveReviews {
  reviews: ReviewSummary[];
  loading: boolean;
  error: string | null;
  /** Whether archived rows are included. Lives here because this is where the fetch is. */
  showArchived: boolean;
  setShowArchived: (value: boolean) => void;
}

/** A failure while the window leaves for a login or a logout is that departure, not something to report. */
function reported(error: Error | null): string | null {
  return error && !isLeavingForAuth() ? error.message : null;
}

/**
 * The reviews list, kept live. Archived rows are a separate query, read by REST only and never touched by the
 * socket: its snapshot frame REPLACES the live rows and carries live rows only, so archived rows kept in that list
 * would vanish on every reconnect, and with a five-minute session cookie a reconnect is the ordinary case.
 */
export function useLiveReviews(): LiveReviews {
  const client = useQueryClient();
  const [showArchived, setShowArchived] = useState(false);
  const live = useQuery({
    queryKey: REVIEWS_KEY,
    queryFn: async () => sortReviews((await fetchReviews()).filter(isReviewSummary)),
  });
  const archived = useQuery({
    queryKey: ARCHIVED_REVIEWS_KEY,
    // The listing returns live rows too; the live half already has a feed, and two copies would collide on key.
    queryFn: async () => (await fetchReviews(true)).filter(isReviewSummary).filter((r) => r.archivedAt !== null),
    enabled: showArchived,
  });

  useLiveSocket({
    path: '/api/ws/reviews',
    onFrame: (frame) => {
      if (Array.isArray(frame)) writeLive(client, REVIEWS_KEY, sortReviews(frame.filter(isReviewSummary)));
      // Archiving broadcasts a removal too: the row leaves the live list and stays readable as archived.
      else if (isRemoval(frame)) updateLive<ReviewSummary[]>(client, REVIEWS_KEY, (rows) => (rows ?? []).filter((r) => r.id !== frame.removed));
      else if (isReviewSummary(frame)) updateLive<ReviewSummary[]>(client, REVIEWS_KEY, (rows) => sortReviews(upsertBy(rows ?? [], frame, (r) => r.id)));
    },
  });

  const reviews = useMemo(
    () => (showArchived ? sortReviews([...(live.data ?? []), ...(archived.data ?? [])]) : live.data ?? []),
    [live.data, archived.data, showArchived],
  );
  const liveError = reported(live.error);
  const error = liveError ?? (showArchived ? reported(archived.error) : null);
  return { reviews, loading: live.data === undefined && liveError === null, error, showArchived, setShowArchived };
}
