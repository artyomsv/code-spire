import { useEffect, useState } from 'react';
import { fetchAttention, fetchWebhookAttention, type AttentionItem } from '../api';
import { onAttentionChanged } from '../attentionSignal';

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
  dismiss: null,
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
  dismiss: null,
};

function bySeverityThenCode(a: AttentionItem, b: AttentionItem): number {
  const bySeverity = SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity];
  if (bySeverity !== 0) return bySeverity;
  const byCode = a.code.localeCompare(b.code);
  return byCode !== 0 ? byCode : (a.subject ?? '').localeCompare(b.subject ?? '');
}

/**
 * @returns the merged rows, and a `refresh` that re-reads both feeds immediately — so acknowledging
 *          a row updates the bell at once instead of leaving a dismissed row on screen for up to a
 *          full poll interval.
 */
export function useAttention(): { items: AttentionItem[]; refresh: () => void } {
  const [items, setItems] = useState<AttentionItem[]>([]);
  // Bumping this re-runs the effect, which reloads and restarts the interval. Re-running the effect
  // rather than calling a hoisted loader keeps the cancelled-flag guard in one place.
  const [reloadCount, setReloadCount] = useState(0);

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

    // A mutation that could clear a row — a credential Check, a dead-letter replay — re-reads at
    // once. Without this the operator fixes the cause and the row lingers for the rest of the
    // interval, which reads as the panel being broken rather than merely late.
    const unsubscribe = onAttentionChanged(() => void load());

    // Browsers throttle timers hard in background tabs, so a panel left in an inactive tab can be
    // arbitrarily stale by the time it is looked at again. Re-read on return rather than trusting
    // that the interval kept running.
    const onFocus = () => void load();
    window.addEventListener('focus', onFocus);

    return () => {
      cancelled = true;
      clearInterval(timer);
      unsubscribe();
      window.removeEventListener('focus', onFocus);
    };
  }, [reloadCount]);

  return { items, refresh: () => setReloadCount((n) => n + 1) };
}
