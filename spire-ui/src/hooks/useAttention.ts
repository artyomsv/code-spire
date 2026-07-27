import { useEffect, useState } from 'react';
import { fetchAttention, fetchWebhookAttention, type AttentionItem } from '../api';

/**
 * Conditions are derived on the server on every request, so there is nothing to subscribe to —
 * a poll is both sufficient and impossible to get stale. 30s keeps the badge honest without
 * making the bell a load source.
 */
const POLL_MS = 30_000;

const SEVERITY_RANK: Record<AttentionItem['severity'], number> = { BLOCKING: 0, WARNING: 1 };

/** The gateway being unreachable means no webhook is arriving at all. */
const GATEWAY_UNREACHABLE: AttentionItem = {
  code: 'GATEWAY_UNREACHABLE',
  severity: 'BLOCKING',
  subject: null,
  message: 'The webhook gateway is not responding, so no pull request event can arrive.',
  action: null,
};

/**
 * The orchestrator feed can fail on its own (a DB blip, pool exhaustion) while the app is
 * otherwise up. Dropping its rows silently would show an empty panel — a claim of "all clear"
 * the app cannot make, since it never actually evaluated those conditions.
 */
const ATTENTION_UNAVAILABLE: AttentionItem = {
  code: 'ATTENTION_UNAVAILABLE',
  severity: 'BLOCKING',
  subject: null,
  message: 'Some conditions could not be loaded, so this list may be incomplete.',
  action: null,
};

function bySeverityThenCode(a: AttentionItem, b: AttentionItem): number {
  const bySeverity = SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity];
  if (bySeverity !== 0) return bySeverity;
  const byCode = a.code.localeCompare(b.code);
  return byCode !== 0 ? byCode : (a.subject ?? '').localeCompare(b.subject ?? '');
}

export function useAttention(): { items: AttentionItem[] } {
  const [items, setItems] = useState<AttentionItem[]>([]);

  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      // Settled, not all: one service being down must never blank the other's rows.
      const [orchestrator, gateway] = await Promise.allSettled([
        fetchAttention(),
        fetchWebhookAttention(),
      ]);
      if (cancelled) return;
      const merged: AttentionItem[] = [];
      if (orchestrator.status === 'fulfilled') merged.push(...orchestrator.value);
      else merged.push(ATTENTION_UNAVAILABLE);
      if (gateway.status === 'fulfilled') merged.push(...gateway.value);
      else merged.push(GATEWAY_UNREACHABLE);
      setItems(merged.sort(bySeverityThenCode));
    };

    void load();
    const timer = setInterval(() => void load(), POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  return { items };
}
