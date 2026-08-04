import { useEffect, useRef, useState } from 'react';
import { fetchReviews, type ReviewSummary } from './api';
import { fetchMe, goToLogin, isLeavingForAuth, needsLogin } from './auth';

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

function upsert(prev: ReviewSummary[], next: ReviewSummary): ReviewSummary[] {
  const idx = prev.findIndex((r) => r.id === next.id);
  const merged = idx >= 0 ? prev.map((r) => (r.id === next.id ? next : r)) : [...prev, next];
  return sortReviews(merged);
}

export interface LiveReviews {
  reviews: ReviewSummary[];
  loading: boolean;
  error: string | null;
}

export function useLiveReviews(): LiveReviews {
  const [reviews, setReviews] = useState<ReviewSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Once the WebSocket has delivered data, the (possibly slower) REST snapshot
  // is stale — applying it would overwrite fresher live state.
  const wsDelivered = useRef(false);

  // Initial snapshot via REST — drives loading / empty / error states.
  useEffect(() => {
    let active = true;
    fetchReviews()
      .then((list) => {
        if (!active || wsDelivered.current) return;
        setReviews(sortReviews(list.filter(isReviewSummary)));
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (!active || wsDelivered.current) return;
        // Not an error worth showing: the window is already on its way to a login or a logout, and
        // this call failed *because* of that. Reporting it painted a red failure over the dashboard
        // for the moment before the page went away — most visibly on logout, where the operator got
        // an alarming message for doing exactly what they intended.
        if (isLeavingForAuth()) return;
        setError(e instanceof Error ? e.message : 'Failed to load reviews');
        setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  // Live updates: array = snapshot (replace); object = single upsert.
  useEffect(() => {
    let ws: WebSocket | null = null;
    let closed = false;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;

    const connect = () => {
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      ws = new WebSocket(`${proto}//${location.host}/api/ws/reviews`);
      ws.onmessage = (ev) => {
        let data: unknown;
        try {
          data = JSON.parse(ev.data);
        } catch {
          return;
        }
        if (Array.isArray(data)) {
          wsDelivered.current = true;
          setReviews(sortReviews(data.filter(isReviewSummary)));
          setLoading(false);
        } else if (isRemoval(data)) {
          const removedId = data.removed;
          setReviews((prev) => prev.filter((r) => r.id !== removedId));
        } else if (isReviewSummary(data)) {
          wsDelivered.current = true;
          setReviews((prev) => upsert(prev, data));
          setLoading(false);
        }
      };
      ws.onclose = () => {
        if (closed) return;
        if (isLeavingForAuth()) return; // the session is being handed back; nothing to diagnose
        // A closed socket is not automatically a network blip. An unauthenticated or expired
        // handshake is answered with a redirect the browser cannot follow, so the socket fails with
        // no useful close code — and the session cookie's default lifetime is five minutes, which
        // makes expiry the ordinary case rather than the rare one. Reconnecting unconditionally
        // therefore hammered the identity provider several times a second, forever, on every
        // expiry. Ask why it closed before trying again.
        void fetchMe().then((me) => {
          if (closed) return;
          if (needsLogin(me)) {
            goToLogin();
            return;
          }
          reconnectTimer = setTimeout(connect, 1500);
        });
      };
      ws.onerror = () => {
        ws?.close();
      };
    };

    connect();
    return () => {
      closed = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      ws?.close();
    };
  }, []);

  return { reviews, loading, error };
}
