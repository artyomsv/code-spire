import { useEffect, useState } from 'react';
import { ensureServiceSessions, fetchMe, goToFullLogin, isLeavingForAuth, needsLogin } from '../auth';
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
  { key: 'orchestrator', path: '/api/ws/attention' },
  { key: 'gateway', path: '/gw/ws/webhook-attention' },
] as const;

type FeedKey = (typeof FEEDS)[number]['key'];

/**
 * What a feed is currently telling us.
 *
 * <p>{@code connecting} exists to keep "not yet known" distinct from "known bad". Collapsing the two
 * made every page load flash a red badge claiming both services were unreachable, for as long as the
 * sockets took to open — a false BLOCKING alarm on every refresh, and an indefinite one had a service
 * been slow. A feed that has not answered yet contributes nothing, because nothing has been
 * established about it.
 */
type FeedState =
  | { status: 'connecting' }
  | { status: 'live'; rows: AttentionItem[] }
  | { status: 'down' };

/**
 * Synthesized once a feed's socket has actually failed — never merely because it has not answered
 * yet. A closed socket is a better signal than a failed request was: it is known the instant the
 * connection drops rather than at the next poll.
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
  const [feeds, setFeeds] = useState<Record<FeedKey, FeedState>>({
    orchestrator: { status: 'connecting' },
    gateway: { status: 'connecting' },
  });

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
        setFeeds((prev) => ({ ...prev, [key]: { status: 'live', rows: data.filter(isAttentionItem) } }));
      };

      // Drop this feed's rows rather than leaving stale ones on screen: while the socket is down we
      // do not know what it would say, and the synthesized row states exactly that. Reaching 'down'
      // requires an actual close or error — never merely a socket that has yet to open.
      const onGone = () => {
        if (closed) return;
        // A logout closes every socket. Without this the panel raised BLOCKING rows claiming the
        // services were unreachable, over the top of a page that was already navigating away.
        if (isLeavingForAuth()) return;
        // Ask WHY it closed before reacting. An unauthenticated or expired handshake fails with no
        // usable close code, and the session's default lifetime is five minutes — so without this
        // check every expiry both reconnected forever and reported itself as an outage, telling the
        // operator "the webhook gateway is not responding" when the gateway was fine and the
        // session had simply lapsed. Diagnosing an auth problem as an outage is worse than silence.
        void fetchMe().then(async (me) => {
          if (closed) return;
          if (needsLogin(me)) {
            goToFullLogin();
            return;
          }
          // Signed in here does not mean signed in THERE: each service is a separate session behind
          // its own prefix, and a handshake cannot follow the redirect that says so. Without this
          // check a lapsed — or never-established — gateway session read as "the webhook gateway is
          // not responding", which is the same false-outage report this whole branch exists to avoid,
          // arrived at from the other direction.
          if (await ensureServiceSessions()) return;
          if (closed) return;
          setFeeds((prev) => ({ ...prev, [key]: { status: 'down' } }));
          timers.push(setTimeout(() => connect({ key, path }), RECONNECT_MS));
        });
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

  const items = FEEDS.flatMap(({ key }) => {
    const feed = feeds[key];
    switch (feed.status) {
      case 'live':
        return feed.rows;
      case 'down':
        return [UNAVAILABLE[key]];
      case 'connecting':
        // Nothing has been established yet, so claim nothing. Reporting the feed as unreachable here
        // would raise a blocking alarm about a socket that is merely still opening.
        return [];
    }
  }).sort(bySeverityThenCode);

  return { items };
}
