import { useEffect, useState } from 'react';
import { fetchReviews, type ReviewSummary } from './api';

function sortReviews(list: ReviewSummary[]): ReviewSummary[] {
  return [...list].sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt));
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

  // Initial snapshot via REST — drives loading / empty / error states.
  useEffect(() => {
    let active = true;
    fetchReviews()
      .then((list) => {
        if (!active) return;
        setReviews(sortReviews(list));
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (!active) return;
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
          setReviews(sortReviews(data as ReviewSummary[]));
          setLoading(false);
        } else if (data && typeof data === 'object') {
          setReviews((prev) => upsert(prev, data as ReviewSummary));
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
