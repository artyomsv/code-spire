import { useEffect, useRef, useState } from 'react';
import { fetchReviews, type ReviewSummary } from './api';

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
      ws = new WebSocket(`${proto}//${location.host}/ws/reviews`);
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
        } else if (isReviewSummary(data)) {
          wsDelivered.current = true;
          setReviews((prev) => upsert(prev, data));
          setLoading(false);
        }
      };
      ws.onclose = () => {
        if (closed) return;
        reconnectTimer = setTimeout(connect, 1500);
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
