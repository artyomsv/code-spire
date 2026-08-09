import { useEffect, useMemo, useRef, useState } from 'react';
import { fetchReviews, type ReviewSummary } from './api';
import { fetchMe, goToFullLogin, isLeavingForAuth, needsLogin } from './auth';

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
  /** Whether archived rows are included. Lives here because this is where the fetch is. */
  showArchived: boolean;
  setShowArchived: (value: boolean) => void;
}

export function useLiveReviews(): LiveReviews {
  const [live, setLive] = useState<ReviewSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /**
   * Archived rows are kept in their own list, fetched by REST and never touched by the socket.
   *
   * <p>Not a flag on one shared list, because the socket's snapshot frame REPLACES that list
   * (below) and carries live rows only — archived rows folded into it would disappear on every
   * reconnect, and with a five-minute session cookie a reconnect is the ordinary case. An archived
   * review is frozen anyway, so there is nothing for a live feed to tell it.
   */
  const [archived, setArchived] = useState<ReviewSummary[]>([]);
  const [showArchived, setShowArchived] = useState(false);

  // Once the WebSocket has delivered data, the (possibly slower) REST snapshot
  // is stale — applying it would overwrite fresher live state.
  const wsDelivered = useRef(false);

  // Initial snapshot via REST — drives loading / empty / error states.
  useEffect(() => {
    let active = true;
    fetchReviews()
      .then((list) => {
        if (!active || wsDelivered.current) return;
        setLive(sortReviews(list.filter(isReviewSummary)));
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
          setLive(sortReviews(data.filter(isReviewSummary)));
          setLoading(false);
        } else if (isRemoval(data)) {
          // Archiving broadcasts a removal too: the row leaves the LIVE list. It stays readable
          // through the archived fetch, which is where it now belongs.
          const removedId = data.removed;
          setLive((prev) => prev.filter((r) => r.id !== removedId));
        } else if (isReviewSummary(data)) {
          wsDelivered.current = true;
          setLive((prev) => upsert(prev, data));
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
            // No session at all, so ask for every prefix in one sequence rather than letting each be
            // discovered missing a page load at a time.
            goToFullLogin();
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

  // Archived rows, on request only. The listing returns live rows too, so it is narrowed to the
  // archived ones — the live half already has a feed, and two copies of a row would collide on key.
  useEffect(() => {
    if (!showArchived) {
      setArchived([]);
      return;
    }
    let active = true;
    fetchReviews(true)
      .then((list) => {
        if (!active) return;
        setArchived(list.filter(isReviewSummary).filter((r) => r.archivedAt !== null));
      })
      .catch((e: unknown) => {
        // Reported rather than swallowed: the operator asked to see archived work, and an empty
        // result that means "the request failed" is indistinguishable from "there is none".
        if (!active || isLeavingForAuth()) return;
        setError(e instanceof Error ? e.message : 'Failed to load archived reviews');
      });
    return () => {
      active = false;
    };
  }, [showArchived]);

  const reviews = useMemo(
    () => (showArchived ? sortReviews([...live, ...archived]) : live),
    [live, archived, showArchived],
  );

  return { reviews, loading, error, showArchived, setShowArchived };
}
