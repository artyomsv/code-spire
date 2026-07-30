import { useEffect, useState } from 'react';
import type { AttentionItem } from '../api';

/**
 * How long to wait before reopening a socket that dropped. Long enough not to hammer a service that
 * is restarting, short enough that a deploy does not leave the panel blind for long.
 */
const RECONNECT_MS = 1500;

const SEVERITY_RANK: Record<AttentionItem['severity'], number> = { BLOCKING: 0, WARNING: 1 };

/**
 * Each service pushes the conditions it owns, because neither reads the other's schema — so there is
 * no aggregating service that could hold a single socket. Two sockets, merged here, mirroring what
 * the two HTTP feeds did before.
 */
const FEEDS = [
  { key: 'orchestrator', path: '/ws/attention' },
  { key: 'gateway', path: '/ws/webhook-attention' },
] as const;

type FeedKey = (typeof FEEDS)[number]['key'];

/**
 * Synthesized when a feed's socket is not open. A closed socket is a better signal than a failed
 * request was: it is known the instant the connection drops rather than at the next poll.
 */
const UNAVAILABLE: Record<FeedKey, AttentionItem> = {
  gateway: {
    code: 'GATEWAY_UNREACHABLE',
    severity: 'BLOCKING',
    subject: null,
    message: 'The webhook gateway is not responding, so no pull request event can arrive.',
    action: null,
    dismiss: null,
  },
  orchestrator: {
    code: 'ATTENTION_UNAVAILABLE',
    severity: 'BLOCKING',
    subject: null,
    message: 'Some conditions could not be loaded, so this list may be incomplete.',
    action: null,
    dismiss: null,
  },
};

function bySeverityThenCode(a: AttentionItem, b: AttentionItem): number {
  const bySeverity = SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity];
  if (bySeverity !== 0) return bySeverity;
  const byCode = a.code.localeCompare(b.code);
  return byCode !== 0 ? byCode : (a.subject ?? '').localeCompare(b.subject ?? '');
}

function isAttentionItem(value: unknown): value is AttentionItem {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as AttentionItem).code === 'string' &&
    typeof (value as AttentionItem).message === 'string'
  );
}

/**
 * The operator conditions both services are currently reporting.
 *
 * <p>Pushed, not polled. The panel used to fetch on a timer, which meant fixing a cause and then
 * waiting up to an interval for the row to go — indistinguishable, to the operator, from the panel
 * being broken. Each service now pushes its conditions when they change, and re-sends the whole list
 * rather than a delta: a row is derived state with no identity to address an incremental update to,
 * and a handful of entries is cheaper to resend than a diff protocol is to keep correct.
 *
 * <p>A feed whose socket is not open contributes a synthesized BLOCKING row instead of contributing
 * nothing, because an empty panel would otherwise claim "all clear" about conditions nobody
 * evaluated.
 */
export function useAttention(): { items: AttentionItem[] } {
  const [rowsByFeed, setRowsByFeed] = useState<Partial<Record<FeedKey, AttentionItem[]>>>({});

  useEffect(() => {
    let closed = false;
    const sockets: WebSocket[] = [];
    const timers: ReturnType<typeof setTimeout>[] = [];

    const connect = ({ key, path }: { key: FeedKey; path: string }) => {
      if (closed) return;
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      const ws = new WebSocket(`${proto}//${location.host}${path}`);
      sockets.push(ws);

      ws.onmessage = (ev) => {
        let data: unknown;
        try {
          data = JSON.parse(ev.data);
        } catch {
          return; // a malformed frame must not blank a feed that was working
        }
        if (!Array.isArray(data)) return;
        setRowsByFeed((prev) => ({ ...prev, [key]: data.filter(isAttentionItem) }));
      };

      // Drop this feed's rows rather than leaving stale ones on screen: while the socket is down we
      // do not know what it would say, and the synthesized row states exactly that.
      const onGone = () => {
        if (closed) return;
        setRowsByFeed((prev) => {
          const next = { ...prev };
          delete next[key];
          return next;
        });
        timers.push(setTimeout(() => connect({ key, path }), RECONNECT_MS));
      };
      ws.onclose = onGone;
      ws.onerror = () => ws.close();
    };

    FEEDS.forEach((feed) => connect(feed));

    return () => {
      closed = true;
      timers.forEach(clearTimeout);
      sockets.forEach((ws) => ws.close());
    };
  }, []);

  const items = FEEDS.flatMap(({ key }) => rowsByFeed[key] ?? [UNAVAILABLE[key]]).sort(
    bySeverityThenCode,
  );

  return { items };
}
